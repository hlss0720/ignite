/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.cache.eviction.igfs.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.igfs.mapreduce.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.managers.communication.*;
import org.apache.ignite.internal.managers.eventstorage.*;
import org.apache.ignite.internal.processors.task.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.igfs.IgfsMode.*;
import static org.apache.ignite.internal.IgniteNodeAttributes.*;
import static org.apache.ignite.internal.GridTopic.*;
import static org.apache.ignite.internal.processors.igfs.IgfsFileInfo.*;

/**
 * Cache-based IGFS implementation.
 */
public final class IgfsImpl implements IgfsEx {
    /** Default permissions for file system entry. */
    private static final String PERMISSION_DFLT_VAL = "0777";

    /** Default directory metadata. */
    private static final Map<String, String> DFLT_DIR_META = F.asMap(PROP_PERMISSION, PERMISSION_DFLT_VAL);

    /** Handshake message. */
    private final IgfsPaths secondaryPaths;

    /** Cache based structure (meta data) manager. */
    private IgfsMetaManager meta;

    /** Cache based file's data container. */
    private IgfsDataManager data;

    /** FS configuration. */
    private IgfsConfiguration cfg;

    /** IGFS context. */
    private IgfsContext igfsCtx;

    /** Event storage manager. */
    private GridEventStorageManager evts;

    /** Local node. */
    private ClusterNode locNode;

    /** Logger. */
    private IgniteLogger log;

    /** Mode resolver. */
    private final IgfsModeResolver modeRslvr;

    /** Connection to the secondary file system. */
    private Igfs secondaryFs;

    /** Busy lock. */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** Writers map. */
    private final ConcurrentHashMap8<IgfsPath, IgfsFileWorker> workerMap = new ConcurrentHashMap8<>();

    /** Delete futures. */
    private final ConcurrentHashMap8<IgniteUuid, GridFutureAdapter<Object>> delFuts = new ConcurrentHashMap8<>();

    /** Delete message listener. */
    private final GridMessageListener delMsgLsnr = new FormatMessageListener();

    /** Format discovery listener. */
    private final GridLocalEventListener delDiscoLsnr = new FormatDiscoveryListener();

    /** Local metrics holder. */
    private final IgfsLocalMetrics metrics = new IgfsLocalMetrics();

    /** Client log directory. */
    private volatile String logDir;

    /** Message topic. */
    private Object topic;

    /** Eviction policy (if set). */
    private CacheIgfsPerBlockLruEvictionPolicy evictPlc;

    /**
     * Creates IGFS instance with given context.
     *
     * @param igfsCtx Context.
     * @throws IgniteCheckedException In case of error.
     */
    IgfsImpl(IgfsContext igfsCtx) throws IgniteCheckedException {
        assert igfsCtx != null;

        this.igfsCtx = igfsCtx;

        cfg = igfsCtx.configuration();
        log = igfsCtx.kernalContext().log(IgfsImpl.class);
        evts = igfsCtx.kernalContext().event();
        meta = igfsCtx.meta();
        data = igfsCtx.data();
        secondaryFs = cfg.getSecondaryFileSystem();

        /* Default IGFS mode. */
        IgfsMode dfltMode;

        if (secondaryFs == null) {
            if (cfg.getDefaultMode() == PROXY)
                throw new IgniteCheckedException("Mode cannot be PROXY if secondary file system hasn't been defined.");

            dfltMode = PRIMARY;
        }
        else
            dfltMode = cfg.getDefaultMode();

        Map<String, IgfsMode> cfgModes = new LinkedHashMap<>();
        Map<String, IgfsMode> dfltModes = new LinkedHashMap<>(4, 1.0f);

        dfltModes.put("/ignite/primary", PRIMARY);

        if (secondaryFs != null) {
            dfltModes.put("/ignite/proxy", PROXY);
            dfltModes.put("/ignite/sync", DUAL_SYNC);
            dfltModes.put("/ignite/async", DUAL_ASYNC);
        }

        cfgModes.putAll(dfltModes);

        if (igfsCtx.configuration().getPathModes() != null) {
            for (Map.Entry<String, IgfsMode> e : igfsCtx.configuration().getPathModes().entrySet()) {
                if (!dfltModes.containsKey(e.getKey()))
                    cfgModes.put(e.getKey(), e.getValue());
                else
                    U.warn(log, "Ignoring path mode because it conflicts with Ignite reserved path " +
                        "(use another path) [mode=" + e.getValue() + ", path=" + e.getKey() + ']');
            }
        }

        ArrayList<T2<IgfsPath, IgfsMode>> modes = null;

        if (!cfgModes.isEmpty()) {
            modes = new ArrayList<>(cfgModes.size());

            for (Map.Entry<String, IgfsMode> mode : cfgModes.entrySet()) {
                IgfsMode mode0 = secondaryFs == null ? mode.getValue() == PROXY ? PROXY : PRIMARY : mode.getValue();

                try {
                    modes.add(new T2<>(new IgfsPath(mode.getKey()), mode0));
                }
                catch (IllegalArgumentException e) {
                    throw new IgniteCheckedException("Invalid path found in mode pattern: " + mode.getKey(), e);
                }
            }
        }

        modeRslvr = new IgfsModeResolver(dfltMode, modes);

        secondaryPaths = new IgfsPaths(secondaryFs == null ? null : secondaryFs.properties(), dfltMode,
            modeRslvr.modesOrdered());

        // Check whether IGFS LRU eviction policy is set on data cache.
        String dataCacheName = igfsCtx.configuration().getDataCacheName();

        for (CacheConfiguration cacheCfg : igfsCtx.kernalContext().config().getCacheConfiguration()) {
            if (F.eq(dataCacheName, cacheCfg.getName())) {
                CacheEvictionPolicy evictPlc = cacheCfg.getEvictionPolicy();

                if (evictPlc != null & evictPlc instanceof CacheIgfsPerBlockLruEvictionPolicy)
                    this.evictPlc = (CacheIgfsPerBlockLruEvictionPolicy)evictPlc;

                break;
            }
        }

        topic = F.isEmpty(name()) ? TOPIC_IGFS : TOPIC_IGFS.topic(name());

        igfsCtx.kernalContext().io().addMessageListener(topic, delMsgLsnr);
        igfsCtx.kernalContext().event().addLocalEventListener(delDiscoLsnr, EVT_NODE_LEFT, EVT_NODE_FAILED);
    }

    /**
     * @return Local node.
     */
    private ClusterNode localNode() {
        if (locNode == null)
            locNode = igfsCtx.kernalContext().discovery().localNode();

        return locNode;
    }

    /** {@inheritDoc} */
    @Override public void stop() {
        busyLock.block();

        // Clear interrupted flag temporarily.
        boolean interrupted = Thread.interrupted();

        // Force all workers to finish their batches.
        for (IgfsFileWorker w : workerMap.values())
            w.cancel();

        // Wait for all writers to finish their execution.
        for (IgfsFileWorker w : workerMap.values()) {
            try {
                w.join();
            }
            catch (InterruptedException e) {
                U.error(log, e.getMessage(), e);
            }
        }

        workerMap.clear();

        if (secondaryFs instanceof AutoCloseable)
            U.closeQuiet((AutoCloseable)secondaryFs);

        igfsCtx.kernalContext().io().removeMessageListener(topic, delMsgLsnr);
        igfsCtx.kernalContext().event().removeLocalEventListener(delDiscoLsnr);

        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Create batch for the file.
     *
     * @param path File path in the secondary file system.
     * @param out Output stream to that file.
     * @return Created batch.
     * @throws IgniteCheckedException In case new batch cannot be created.
     */
    private IgfsFileWorkerBatch newBatch(final IgfsPath path, OutputStream out) throws IgniteCheckedException {
        assert path != null;
        assert out != null;

        if (enterBusy()) {
            try {
                IgfsFileWorkerBatch batch = new IgfsFileWorkerBatch(path, out);

                while (true) {
                    IgfsFileWorker worker = workerMap.get(path);

                    if (worker != null) {
                        if (worker.addBatch(batch)) // Added batch to active worker.
                            break;
                        else
                            workerMap.remove(path, worker); // Worker is stopping. Remove it from map.
                    }
                    else {
                        worker = new IgfsFileWorker("igfs-file-worker-" + path) {
                            @Override protected void onFinish() {
                                workerMap.remove(path, this);
                            }
                        };

                        boolean b = worker.addBatch(batch);

                        assert b;

                        if (workerMap.putIfAbsent(path, worker) == null) {
                            worker.start();

                            break;
                        }
                    }
                }

                return batch;
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IgniteCheckedException("Cannot create new output stream to the secondary file system because IGFS is " +
                "stopping: " + path);
    }

    /**
     * @return {@code True} if entered busy section.
     * @throws IgniteException If failed to await caches start.
     */
    private boolean enterBusy() {
        meta.awaitInit();
        data.awaitInit();

        return busyLock.enterBusy();
    }

    /**
     * Await for any pending finished writes on the children paths.
     *
     * @param paths Paths to check.
     */
    void await(IgfsPath... paths) {
        assert paths != null;

        for (Map.Entry<IgfsPath, IgfsFileWorker> workerEntry : workerMap.entrySet()) {
            IgfsPath workerPath = workerEntry.getKey();

            boolean await = false;

            for (IgfsPath path : paths) {
                if (workerPath.isSubDirectoryOf(path) || workerPath.isSame(path))  {
                    await = true;

                    break;
                }
            }

            if (await) {
                IgfsFileWorkerBatch batch = workerEntry.getValue().currentBatch();

                if (batch != null) {
                    try {
                        batch.awaitIfFinished();
                    }
                    catch (IgniteCheckedException ignore) {
                        // No-op.
                    }
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override public IgfsContext context() {
        return igfsCtx;
    }

    /**
     * @return Mode resolver.
     */
    IgfsModeResolver modeResolver() {
        return modeRslvr;
    }

    /** {@inheritDoc} */
    @Nullable @Override public String name() {
        return cfg.getName();
    }

    /** {@inheritDoc} */
    @Override public IgfsConfiguration configuration() {
        return cfg;
    }

    /** {@inheritDoc} */
    @Override public IgfsPaths proxyPaths() {
        return secondaryPaths;
    }

    /** {@inheritDoc} */
    @Override public String clientLogDirectory() {
        return logDir;
    }

    /** {@inheritDoc} */
    @Override public void clientLogDirectory(String logDir) {
        this.logDir = logDir;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("ConstantConditions")
    @Override public IgfsStatus globalSpace() throws IgniteCheckedException {
        if (enterBusy()) {
            try {
                IgniteBiTuple<Long, Long> space = igfsCtx.kernalContext().grid().compute().execute(
                    new IgfsGlobalSpaceTask(name()), null);

                return new IgfsStatus(space.get1(), space.get2());
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get global space because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public void globalSampling(@Nullable Boolean val) throws IgniteCheckedException {
        if (enterBusy()) {
            try {
                if (meta.sampling(val)) {
                    if (val == null)
                        log.info("Sampling flag has been cleared. All further file system connections will perform " +
                            "logging depending on their configuration.");
                    else if (val)
                        log.info("Sampling flag has been set to \"true\". All further file system connections will " +
                            "perform logging.");
                    else
                        log.info("Sampling flag has been set to \"false\". All further file system connections will " +
                            "not perform logging.");
                }
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set global sampling flag because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override @Nullable public Boolean globalSampling() {
        if (enterBusy()) {
            try {
                try {
                    return meta.sampling();
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to get sampling state.", e);

                    return false;
                }
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get global sampling flag because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsLocalMetrics localMetrics() {
        return metrics;
    }

    /** {@inheritDoc} */
    @Override public long groupBlockSize() {
        return data.groupBlockSize();
    }

    /** {@inheritDoc} */
    @Override public boolean exists(IgfsPath path) {
        try {
            A.notNull(path, "path");

            if (log.isDebugEnabled())
                log.debug("Check file exists: " + path);

            IgfsMode mode = modeRslvr.resolveMode(path);

            if (mode == PROXY)
                throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

            boolean res = false;

            switch (mode) {
                case PRIMARY:
                    res = meta.fileId(path) != null;

                    break;

                case DUAL_SYNC:
                case DUAL_ASYNC:
                    res = meta.fileId(path) != null;

                    if (!res)
                        res = secondaryFs.exists(path);

                    break;

                default:
                    assert false : "Unknown mode.";
            }

            return res;
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public IgfsFile info(IgfsPath path) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("Get file info: " + path);

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

                IgfsFileInfo info = resolveFileInfo(path, mode);

                if (info == null)
                    return null;

                return new IgfsFileImpl(path, info, data.groupBlockSize());
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get path info because grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsPathSummary summary(IgfsPath path) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("Calculating path summary: " + path);

                IgniteUuid fileId = meta.fileId(path);

                if (fileId == null)
                    throw new IgfsFileNotFoundException("Failed to get path summary (path not found): " + path);

                IgfsPathSummary sum = new IgfsPathSummary(path);

                summary0(fileId, sum);

                return sum;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get path summary because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsFile update(IgfsPath path, Map<String, String> props) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");
                A.notNull(props, "props");
                A.ensure(!props.isEmpty(), "!props.isEmpty()");

                if (log.isDebugEnabled())
                    log.debug("Set file properties [path=" + path + ", props=" + props + ']');

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);
                else if (mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    await(path);

                    IgfsFileInfo info = meta.updateDual(secondaryFs, path, props);

                    if (info == null)
                        return null;

                    return new IgfsFileImpl(path, info, data.groupBlockSize());
                }

                List<IgniteUuid> fileIds = meta.fileIds(path);

                IgniteUuid fileId = fileIds.get(fileIds.size() - 1);

                if (fileId == null)
                    return null;

                IgniteUuid parentId = fileIds.size() > 1 ? fileIds.get(fileIds.size() - 2) : null;

                IgfsFileInfo info = meta.updateProperties(parentId, fileId, path.name(), props);

                if (info != null) {
                    if (evts.isRecordable(EVT_IGFS_META_UPDATED))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_META_UPDATED, props));

                    return new IgfsFileImpl(path, info, data.groupBlockSize());
                }
                else
                    return null;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to update file because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public void rename(IgfsPath src, IgfsPath dest) {
        if (enterBusy()) {
            try {
                A.notNull(src, "src");
                A.notNull(dest, "dest");

                if (log.isDebugEnabled())
                    log.debug("Rename file [src=" + src + ", dest=" + dest + ']');

                IgfsMode mode = modeRslvr.resolveMode(src);
                Set<IgfsMode> childrenModes = modeRslvr.resolveChildrenModes(src);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + src);

                if (src.equals(dest))
                    return; // Rename to itself is a no-op.

                // Cannot rename root directory.
                if (src.parent() == null)
                    throw new IgfsInvalidPathException("Failed to rename root directory.");

                // Cannot move directory of upper level to self sub-dir.
                if (dest.isSubDirectoryOf(src))
                    throw new IgfsInvalidPathException("Failed to rename directory (cannot move directory of " +
                        "upper level to self sub-dir) [src=" + src + ", dest=" + dest + ']');

                if (evictExclude(src, mode == PRIMARY) != evictExclude(dest, modeRslvr.resolveMode(dest) == PRIMARY))
                    throw new IgfsInvalidPathException("Cannot move file to a path with different eviction " +
                        "exclude setting (need to copy and remove)");

                if (!childrenModes.equals(Collections.singleton(PRIMARY))) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    await(src, dest);

                    meta.renameDual(secondaryFs, src, dest);

                    return;
                }

                IgfsPath destParent = dest.parent();

                // Resolve source file info.
                FileDescriptor srcDesc = getFileDescriptor(src);

                // File not found.
                if (srcDesc == null || srcDesc.parentId == null) {
                    if (mode == PRIMARY)
                        checkConflictWithPrimary(src);

                    throw new IgfsFileNotFoundException("Failed to rename (source path not found): " + src);
                }

                String srcFileName = src.name();

                // Resolve destination file info.
                FileDescriptor destDesc = getFileDescriptor(dest);

                String destFileName;

                boolean newDest = destDesc == null;

                if (newDest) {
                    assert destParent != null;

                    // Use parent directory for destination parent and destination path name as destination name.
                    destDesc = getFileDescriptor(destParent);

                    // Destination directory doesn't exist.
                    if (destDesc == null)
                        throw new IgfsFileNotFoundException("Failed to rename (destination directory does not " +
                            "exist): " + dest);

                    destFileName = dest.name();
                }
                else
                    // Use destination directory for destination parent and source path name as destination name.
                    destFileName = srcFileName;

                // Can move only into directory, but not into file.
                if (destDesc.isFile)
                    throw new IgfsParentNotDirectoryException("Failed to rename (destination is not a directory): "
                        + dest);

                meta.move(srcDesc.fileId, srcFileName, srcDesc.parentId, destFileName, destDesc.fileId);

                if (srcDesc.isFile) { // Renamed a file.
                    if (evts.isRecordable(EVT_IGFS_FILE_RENAMED))
                        evts.record(new IgfsEvent(
                            src,
                            newDest ? dest : new IgfsPath(dest, destFileName),
                            localNode(),
                            EVT_IGFS_FILE_RENAMED));
                }
                else { // Renamed a directory.
                    if (evts.isRecordable(EVT_IGFS_DIR_RENAMED))
                        evts.record(new IgfsEvent(src, dest, localNode(), EVT_IGFS_DIR_RENAMED));
                }
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set rename path because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public boolean delete(IgfsPath path, boolean recursive) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("Deleting file [path=" + path + ", recursive=" + recursive + ']');

                IgfsMode mode = modeRslvr.resolveMode(path);
                Set<IgfsMode> childrenModes = modeRslvr.resolveChildrenModes(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

                boolean res = false;

                FileDescriptor desc = getFileDescriptor(path);

                if (childrenModes.contains(PRIMARY)) {
                    if (desc != null)
                        res = delete0(desc, path.parent(), recursive);
                    else if (mode == PRIMARY)
                        checkConflictWithPrimary(path);
                }

                if (childrenModes.contains(DUAL_SYNC) || childrenModes.contains(DUAL_ASYNC)) {
                    assert secondaryFs != null;

                    await(path);

                    res |= meta.deleteDual(secondaryFs, path, recursive);
                }

                // Record event if needed.
                if (res && desc != null) {
                    if (desc.isFile) {
                        if (evts.isRecordable(EVT_IGFS_FILE_DELETED))
                            evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_DELETED));
                    }
                    else if (evts.isRecordable(EVT_IGFS_DIR_DELETED))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_DIR_DELETED));
                }

                return res;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set file times because Grid is stopping.");
    }

    /**
     * Internal procedure for (optionally) recursive file and directory deletion.
     *
     * @param desc File descriptor of file or directory to delete.
     * @param parentPath Parent path. If specified, events will be fired for each deleted file
     *      or directory. If not specified, events will not be fired.
     * @param recursive Recursive deletion flag.
     * @return {@code True} if file was successfully deleted. If directory is not empty and
     *      {@code recursive} flag is false, will return {@code false}.
     * @throws IgniteCheckedException In case of error.
     */
    private boolean delete0(FileDescriptor desc, @Nullable IgfsPath parentPath, boolean recursive)
        throws IgniteCheckedException {
        IgfsPath curPath = parentPath == null ? new IgfsPath() : new IgfsPath(parentPath, desc.fileName);

        if (desc.isFile) {
            deleteFile(curPath, desc, true);

            return true;
        }
        else {
            if (recursive) {
                meta.softDelete(desc.parentId, desc.fileName, desc.fileId);

                return true;
            }
            else {
                Map<String, IgfsListingEntry> infoMap = meta.directoryListing(desc.fileId);

                if (F.isEmpty(infoMap)) {
                    deleteFile(curPath, desc, true);

                    return true;
                }
                else
                    // Throw exception if not empty and not recursive.
                    throw new IgfsDirectoryNotEmptyException("Failed to remove directory (directory is not empty " +
                        "and recursive flag is not set)");
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path) {
        mkdirs(path, null);
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path, @Nullable Map<String, String> props)  {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("Make directories: " + path);

                if (props == null)
                    props = DFLT_DIR_META;

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);
                else if (mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    await(path);

                    meta.mkdirsDual(secondaryFs, path, props);

                    return;
                }

                List<IgniteUuid> ids = meta.fileIds(path);
                List<String> components = path.components();

                assert ids.size() == components.size() + 1 : "Components doesn't contain ROOT element" +
                    " [ids=" + ids + ", components=" + components + ']';

                IgniteUuid parentId = ROOT_ID;

                IgfsPath curPath = path.root();

                for (int step = 0, size = components.size(); step < size; step++) {
                    IgniteUuid fileId = ids.get(step + 1); // Skip the first ROOT element.

                    if (fileId == null) {
                        IgfsFileInfo fileInfo = new IgfsFileInfo(true, props); // Create new directory.

                        String fileName = components.get(step); // Get current component name.

                        curPath = new IgfsPath(curPath, fileName);

                        try {
                            // Fails only if parent is not a directory or if modified concurrently.
                            IgniteUuid oldId = meta.putIfAbsent(parentId, fileName, fileInfo);

                            fileId = oldId == null ? fileInfo.id() : oldId; // Update node ID.

                            if (oldId == null && evts.isRecordable(EVT_IGFS_DIR_CREATED))
                                evts.record(new IgfsEvent(curPath, localNode(), EVT_IGFS_DIR_CREATED));
                        }
                        catch (IgniteCheckedException e) {
                            if (log.isDebugEnabled())
                                log.debug("Failed to create directory [path=" + path + ", parentId=" + parentId +
                                    ", fileName=" + fileName + ", step=" + step + ", e=" + e.getMessage() + ']');

                            // Check directory with such name already exists.
                            IgfsFileInfo stored = meta.info(meta.fileId(parentId, fileName));

                            if (stored == null)
                                throw new IgfsException(e);

                            if (!stored.isDirectory())
                                throw new IgfsParentNotDirectoryException("Failed to create directory (parent " +
                                    "element is not a directory)");

                            fileId = stored.id(); // Update node ID.
                        }
                    }

                    assert fileId != null;

                    parentId = fileId;
                }
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set file times because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsPath> listPaths(final IgfsPath path) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("List directory: " + path);

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

                Set<IgfsMode> childrenModes = modeRslvr.resolveChildrenModes(path);

                Collection<String> files = new HashSet<>();

                if (childrenModes.contains(DUAL_SYNC) || childrenModes.contains(DUAL_ASYNC)) {
                    assert secondaryFs != null;

                    Collection<IgfsPath> children = secondaryFs.listPaths(path);

                    for (IgfsPath child : children)
                        files.add(child.name());
                }

                IgniteUuid fileId = meta.fileId(path);

                if (fileId != null)
                    files.addAll(meta.directoryListing(fileId).keySet());
                else if (mode == PRIMARY) {
                    checkConflictWithPrimary(path);

                    throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);
                }

                return F.viewReadOnly(files, new C1<String, IgfsPath>() {
                    @Override public IgfsPath apply(String e) {
                        return new IgfsPath(path, e);
                    }
                });
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set file times because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsFile> listFiles(final IgfsPath path) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (log.isDebugEnabled())
                    log.debug("List directory details: " + path);

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

                Set<IgfsMode> childrenModes = modeRslvr.resolveChildrenModes(path);

                Collection<IgfsFile> files = new HashSet<>();

                if (childrenModes.contains(DUAL_SYNC) || childrenModes.contains(DUAL_ASYNC)) {
                    assert secondaryFs != null;

                    Collection<IgfsFile> children = secondaryFs.listFiles(path);

                    for (IgfsFile child : children) {
                        IgfsFileInfo fsInfo = new IgfsFileInfo(cfg.getBlockSize(), child.length(),
                            evictExclude(path, false), child.properties());

                        files.add(new IgfsFileImpl(child.path(), fsInfo, data.groupBlockSize()));
                    }
                }

                IgniteUuid fileId = meta.fileId(path);

                if (fileId != null) {
                    IgfsFileInfo info = meta.info(fileId);

                    // Handle concurrent deletion.
                    if (info != null) {
                        if (info.isFile())
                            // If this is a file, return its description.
                            return Collections.<IgfsFile>singleton(new IgfsFileImpl(path, info,
                                data.groupBlockSize()));

                        // Perform the listing.
                        for (Map.Entry<String, IgfsListingEntry> e : info.listing().entrySet()) {
                            IgfsPath p = new IgfsPath(path, e.getKey());

                            files.add(new IgfsFileImpl(p, e.getValue(), data.groupBlockSize()));
                        }
                    }
                }
                else if (mode == PRIMARY) {
                    checkConflictWithPrimary(path);

                    throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);
                }

                return files;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set file times because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public long usedSpaceSize() {
        return metrics().localSpaceSize();
    }

    /** {@inheritDoc} */
    @Override public Map<String, String> properties() {
        return Collections.emptyMap();
    }

    /** {@inheritDoc} */
    @Override public IgfsInputStreamAdapter open(IgfsPath path) {
        return open(path, cfg.getStreamBufferSize(), cfg.getSequentialReadsBeforePrefetch());
    }

    /** {@inheritDoc} */
    @Override public IgfsInputStreamAdapter open(IgfsPath path, int bufSize) {
        return open(path, bufSize, cfg.getSequentialReadsBeforePrefetch());
    }

    /** {@inheritDoc} */
    @Override public IgfsInputStreamAdapter open(IgfsPath path, int bufSize, int seqReadsBeforePrefetch) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");
                A.ensure(bufSize >= 0, "bufSize >= 0");
                A.ensure(seqReadsBeforePrefetch >= 0, "seqReadsBeforePrefetch >= 0");

                if (log.isDebugEnabled())
                    log.debug("Open file for reading [path=" + path + ", bufSize=" + bufSize + ']');

                if (bufSize == 0)
                    bufSize = cfg.getStreamBufferSize();

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);
                else if (mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    IgfsSecondaryInputStreamDescriptor desc = meta.openDual(secondaryFs, path, bufSize);

                    IgfsEventAwareInputStream os = new IgfsEventAwareInputStream(igfsCtx, path, desc.info(),
                        cfg.getPrefetchBlocks(), seqReadsBeforePrefetch, desc.reader(), metrics);

                    if (evts.isRecordable(EVT_IGFS_FILE_OPENED_READ))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_OPENED_READ));

                    return os;
                }

                IgfsFileInfo info = meta.info(meta.fileId(path));

                if (info == null) {
                    checkConflictWithPrimary(path);

                    throw new IgfsFileNotFoundException("File not found: " + path);
                }

                if (!info.isFile())
                    throw new IgfsInvalidPathException("Failed to open file (not a file): " + path);

                // Input stream to read data from grid cache with separate blocks.
                IgfsEventAwareInputStream os = new IgfsEventAwareInputStream(igfsCtx, path, info,
                    cfg.getPrefetchBlocks(), seqReadsBeforePrefetch, null, metrics);

                if (evts.isRecordable(EVT_IGFS_FILE_OPENED_READ))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_OPENED_READ));

                return os;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to open file because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsOutputStream create(IgfsPath path, boolean overwrite) {
        return create0(path, cfg.getStreamBufferSize(), overwrite, null, 0, null, true);
    }

    /** {@inheritDoc} */
    @Override public IgfsOutputStream create(IgfsPath path, int bufSize, boolean overwrite, int replication,
        long blockSize, @Nullable Map<String, String> props) {
        return create0(path, bufSize, overwrite, null, replication, props, false);
    }

    /** {@inheritDoc} */
    @Override public IgfsOutputStream create(IgfsPath path, int bufSize, boolean overwrite,
        @Nullable IgniteUuid affKey, int replication, long blockSize, @Nullable Map<String, String> props) {
        return create0(path, bufSize, overwrite, affKey, replication, props, false);
    }

    /**
     * Create new file.
     *
     * @param path Path.
     * @param bufSize Buffer size.
     * @param overwrite Overwrite flag.
     * @param affKey Affinity key.
     * @param replication Replication factor.
     * @param props Properties.
     * @param simpleCreate Whether new file should be created in secondary FS using create(Path, boolean) method.
     * @return Output stream.
     */
    private IgfsOutputStream create0(
        final IgfsPath path,
        final int bufSize,
        final boolean overwrite,
        @Nullable IgniteUuid affKey,
        final int replication,
        @Nullable Map<String, String> props,
        final boolean simpleCreate
    ) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");
                A.ensure(bufSize >= 0, "bufSize >= 0");

                if (log.isDebugEnabled())
                    log.debug("Open file for writing [path=" + path + ", bufSize=" + bufSize + ", overwrite=" +
                        overwrite + ", props=" + props + ']');

                IgfsMode mode = modeRslvr.resolveMode(path);

                IgfsFileWorkerBatch batch = null;

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);
                else if (mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    await(path);

                    IgfsSecondaryOutputStreamDescriptor desc = meta.createDual(secondaryFs, path, simpleCreate,
                        props, overwrite, bufSize, (short)replication, groupBlockSize(), affKey);

                    batch = newBatch(path, desc.out());

                    IgfsEventAwareOutputStream os = new IgfsEventAwareOutputStream(path, desc.info(), desc.parentId(),
                        bufSize == 0 ? cfg.getStreamBufferSize() : bufSize, mode, batch);

                    if (evts.isRecordable(EVT_IGFS_FILE_OPENED_WRITE))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_OPENED_WRITE));

                    return os;
                }

                // Re-create parents when working in PRIMARY mode. In DUAL mode this is done by MetaManager.
                IgfsPath parent = path.parent();

                // Create missing parent directories if necessary.
                if (parent != null)
                    mkdirs(parent, props);

                List<IgniteUuid> ids = meta.fileIds(path);

                // Resolve parent ID for file.
                IgniteUuid parentId = ids.size() >= 2 ? ids.get(ids.size() - 2) : null;

                if (parentId == null)
                    throw new IgfsInvalidPathException("Failed to resolve parent directory: " + path);

                String fileName = path.name();

                // Constructs new file info.
                IgfsFileInfo info = new IgfsFileInfo(cfg.getBlockSize(), affKey, evictExclude(path, true),
                    props);

                // Add new file into tree structure.
                while (true) {
                    IgniteUuid oldId = meta.putIfAbsent(parentId, fileName, info);

                    if (oldId == null)
                        break;

                    if (!overwrite)
                        throw new IgfsPathAlreadyExistsException("Failed to create file (file already exists): " +
                            path);

                    IgfsFileInfo oldInfo = meta.info(oldId);

                    if (oldInfo.isDirectory())
                        throw new IgfsPathAlreadyExistsException("Failed to create file (path points to a " +
                            "directory): " + path);

                    // Remove old file from the tree.
                    // Only one file is deleted, so we use internal data loader.
                    deleteFile(path, new FileDescriptor(parentId, fileName, oldId, oldInfo.isFile()), false);

                    if (evts.isRecordable(EVT_IGFS_FILE_DELETED))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_DELETED));
                }

                if (evts.isRecordable(EVT_IGFS_FILE_CREATED))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_CREATED));

                info = meta.lock(info.id());

                IgfsEventAwareOutputStream os = new IgfsEventAwareOutputStream(path, info, parentId,
                    bufSize == 0 ? cfg.getStreamBufferSize() : bufSize, mode, batch);

                if (evts.isRecordable(EVT_IGFS_FILE_OPENED_WRITE))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_OPENED_WRITE));

                return os;
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to create file times because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsOutputStream append(IgfsPath path, boolean create) {
        return append(path, cfg.getStreamBufferSize(), create, null);
    }

    /** {@inheritDoc} */
    @Override public IgfsOutputStream append(final IgfsPath path, final int bufSize, boolean create,
        @Nullable Map<String, String> props) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");
                A.ensure(bufSize >= 0, "bufSize >= 0");

                if (log.isDebugEnabled())
                    log.debug("Open file for appending [path=" + path + ", bufSize=" + bufSize + ", create=" + create +
                        ", props=" + props + ']');

                IgfsMode mode = modeRslvr.resolveMode(path);

                IgfsFileWorkerBatch batch = null;

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);
                else if (mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;

                    await(path);

                    IgfsSecondaryOutputStreamDescriptor desc = meta.appendDual(secondaryFs, path, bufSize);

                    batch = newBatch(path, desc.out());

                    return new IgfsEventAwareOutputStream(path, desc.info(), desc.parentId(),
                        bufSize == 0 ? cfg.getStreamBufferSize() : bufSize, mode, batch);
                }

                List<IgniteUuid> ids = meta.fileIds(path);

                IgfsFileInfo info = meta.info(ids.get(ids.size() - 1));

                // Resolve parent ID for the file.
                IgniteUuid parentId = ids.size() >= 2 ? ids.get(ids.size() - 2) : null;

                if (info == null) {
                    if (!create) {
                        checkConflictWithPrimary(path);

                        throw new IgfsFileNotFoundException("File not found: " + path);
                    }

                    if (parentId == null)
                        throw new IgfsInvalidPathException("Failed to resolve parent directory: " + path);

                    info = new IgfsFileInfo(cfg.getBlockSize(), /**affinity key*/null, evictExclude(path,
                        mode == PRIMARY), props);

                    IgniteUuid oldId = meta.putIfAbsent(parentId, path.name(), info);

                    if (oldId != null)
                        info = meta.info(oldId);

                    if (evts.isRecordable(EVT_IGFS_FILE_CREATED))
                        evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_CREATED));
                }

                if (!info.isFile())
                    throw new IgfsInvalidPathException("Failed to open file (not a file): " + path);

                info = meta.lock(info.id());

                if (evts.isRecordable(EVT_IGFS_FILE_OPENED_WRITE))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_OPENED_WRITE));

                return new IgfsEventAwareOutputStream(path, info, parentId, bufSize == 0 ?
                    cfg.getStreamBufferSize() : bufSize, mode, batch);
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to append file times because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public void setTimes(IgfsPath path, long accessTime, long modificationTime) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                if (accessTime == -1 && modificationTime == -1)
                    return;

                FileDescriptor desc = getFileDescriptor(path);

                if (desc == null) {
                    checkConflictWithPrimary(path);

                    throw new IgfsFileNotFoundException("Failed to update times (path not found): " + path);
                }

                // Cannot update times for root.
                if (desc.parentId == null)
                    return;

                meta.updateTimes(desc.parentId, desc.fileId, desc.fileName, accessTime, modificationTime);
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to set file times because Grid is stopping.");
    }

    /**
     * Checks if given path exists in secondary file system and throws exception if so.
     *
     * @param path Path to check.
     * @throws IgniteCheckedException If path exists.
     */
    private void checkConflictWithPrimary(IgfsPath path) throws IgniteCheckedException {
        if (secondaryFs != null) {
            if (secondaryFs.info(path) != null) {
                throw new IgniteCheckedException("Path mapped to a PRIMARY mode found in secondary file " +
                     "system. Remove path from secondary file system or change path mapping: " + path);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsBlockLocation> affinity(IgfsPath path, long start, long len) {
        return affinity(path, start, len, 0L);
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsBlockLocation> affinity(IgfsPath path, long start, long len, long maxLen) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");
                A.ensure(start >= 0, "start >= 0");
                A.ensure(len >= 0, "len >= 0");

                if (log.isDebugEnabled())
                    log.debug("Get affinity for file block [path=" + path + ", start=" + start + ", len=" + len + ']');

                IgfsMode mode = modeRslvr.resolveMode(path);

                if (mode == PROXY)
                    throw new IgniteException("PROXY mode cannot be used in IGFS directly: " + path);

                // Check memory first.
                IgniteUuid fileId = meta.fileId(path);
                IgfsFileInfo info = meta.info(fileId);

                if (info == null && mode != PRIMARY) {
                    assert mode == DUAL_SYNC || mode == DUAL_ASYNC;
                    assert secondaryFs != null;

                    // Synchronize
                    info = meta.synchronizeFileDual(secondaryFs, path);
                }

                if (info == null)
                    throw new IgfsFileNotFoundException("File not found: " + path);

                if (!info.isFile())
                    throw new IgfsInvalidPathException("Failed to get affinity info for file (not a file): " +
                        path);

                return data.affinity(info, start, len, maxLen);
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get affinity because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public IgfsMetrics metrics() {
        if (enterBusy()) {
            try {
                IgfsPathSummary sum = new IgfsPathSummary();

                summary0(ROOT_ID, sum);

                long secondarySpaceSize = 0;

                if (secondaryFs != null) {
                    try {
                        secondarySpaceSize = secondaryFs.usedSpaceSize();
                    }
                    catch (IgniteException e) {
                        LT.warn(log, e, "Failed to get secondary file system consumed space size.");

                        secondarySpaceSize = -1;
                    }
                }

                return new IgfsMetricsAdapter(
                    igfsCtx.data().spaceSize(),
                    igfsCtx.data().maxSpaceSize(),
                    secondarySpaceSize,
                    sum.directoriesCount(),
                    sum.filesCount(),
                    metrics.filesOpenedForRead(),
                    metrics.filesOpenedForWrite(),
                    metrics.readBlocks(),
                    metrics.readBlocksSecondary(),
                    metrics.writeBlocks(),
                    metrics.writeBlocksSecondary(),
                    metrics.readBytes(),
                    metrics.readBytesTime(),
                    metrics.writeBytes(),
                    metrics.writeBytesTime());
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get metrics because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public void resetMetrics() {
        metrics.reset();
    }

    /** {@inheritDoc} */
    @Override public long size(IgfsPath path) {
        if (enterBusy()) {
            try {
                A.notNull(path, "path");

                IgniteUuid nextId = meta.fileId(path);

                if (nextId == null)
                    return 0;

                IgfsPathSummary sum = new IgfsPathSummary(path);

                summary0(nextId, sum);

                return sum.totalLength();
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get path size because Grid is stopping.");
    }

    /**
     * Calculates size of directory or file for given ID.
     *
     * @param fileId File ID.
     * @param sum Summary object that will collect information.
     * @throws IgniteCheckedException If failed.
     */
    private void summary0(IgniteUuid fileId, IgfsPathSummary sum) throws IgniteCheckedException {
        assert sum != null;

        IgfsFileInfo info = meta.info(fileId);

        if (info != null) {
            if (info.isDirectory()) {
                if (!ROOT_ID.equals(info.id()))
                    sum.directoriesCount(sum.directoriesCount() + 1);

                for (IgfsListingEntry entry : info.listing().values())
                    summary0(entry.fileId(), sum);
            }
            else {
                sum.filesCount(sum.filesCount() + 1);
                sum.totalLength(sum.totalLength() + info.length());
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void format() {
        try {
            formatAsync().get();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /**
     * Formats the file system removing all existing entries from it.
     *
     * @return Future.
     */
    IgniteInternalFuture<?> formatAsync() {
        try {
            IgniteUuid id = meta.softDelete(null, null, ROOT_ID);

            if (id == null)
                return new GridFinishedFuture<Object>(igfsCtx.kernalContext());
            else {
                GridFutureAdapter<Object> fut = new GridFutureAdapter<>(igfsCtx.kernalContext());

                GridFutureAdapter<Object> oldFut = delFuts.putIfAbsent(id, fut);

                if (oldFut != null)
                    return oldFut;
                else {
                    if (!meta.exists(id)) {
                        // Safety in case response message was received before we put future into collection.
                        fut.onDone();

                        delFuts.remove(id, fut);
                    }

                    return fut;
                }
            }
        }
        catch (IgniteCheckedException e) {
            return new GridFinishedFuture<Object>(igfsCtx.kernalContext(), e);
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> awaitDeletesAsync() throws IgniteCheckedException {
        Collection<IgniteUuid> ids = meta.pendingDeletes();

        if (!ids.isEmpty()) {
            if (log.isDebugEnabled())
                log.debug("Constructing delete future for trash entries: " + ids);

            GridCompoundFuture<Object, Object> resFut = new GridCompoundFuture<>(igfsCtx.kernalContext());

            for (IgniteUuid id : ids) {
                GridFutureAdapter<Object> fut = new GridFutureAdapter<>(igfsCtx.kernalContext());

                IgniteInternalFuture<Object> oldFut = delFuts.putIfAbsent(id, fut);

                if (oldFut != null)
                    resFut.add(oldFut);
                else {
                    if (meta.exists(id))
                        resFut.add(fut);
                    else {
                        fut.onDone();

                        delFuts.remove(id, fut);
                    }
                }
            }

            resFut.markInitialized();

            return resFut;
        }
        else
            return new GridFinishedFuture<>(igfsCtx.kernalContext());
    }

    /**
     * Get file descriptor for specified path.
     *
     * @param path Path to file.
     * @return Detailed file descriptor or {@code null}, if file does not exist.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private FileDescriptor getFileDescriptor(IgfsPath path) throws IgniteCheckedException {
        List<IgniteUuid> ids = meta.fileIds(path);
        IgfsFileInfo fileInfo = meta.info(ids.get(ids.size() - 1));

        if (fileInfo == null)
            return null; // File does not exist.

        // Resolve parent ID for removed file.
        IgniteUuid parentId = ids.size() >= 2 ? ids.get(ids.size() - 2) : null;

        return new FileDescriptor(parentId, path.name(), fileInfo.id(), fileInfo.isFile());
    }

    /**
     * Remove file from the file system (structure and data).
     *
     * @param path Path of the deleted file.
     * @param desc Detailed file descriptor to remove.
     * @param rmvLocked Whether to remove this entry in case it is has explicit lock.
     * @throws IgniteCheckedException If failed.
     */
    private void deleteFile(IgfsPath path, FileDescriptor desc, boolean rmvLocked) throws IgniteCheckedException {
        IgniteUuid parentId = desc.parentId;
        IgniteUuid fileId = desc.fileId;

        if (parentId == null || ROOT_ID.equals(fileId)) {
            assert parentId == null && ROOT_ID.equals(fileId) : "Invalid file descriptor: " + desc;

            return; // Never remove the root directory!
        }

        if (TRASH_ID.equals(fileId))
            return; // Never remove trash directory.

        meta.removeIfEmpty(parentId, desc.fileName, fileId, path, rmvLocked);
    }

    /**
     * Check whether IGFS with the same name exists among provided attributes.
     *
     * @param attrs Attributes.
     * @return {@code True} in case IGFS with the same name exists among provided attributes
     */
    private boolean sameIgfs(IgfsAttributes[] attrs) {
        if (attrs != null) {
            String igfsName = name();

            for (IgfsAttributes attr : attrs) {
                if (F.eq(igfsName, attr.igfsName()))
                    return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override public <T, R> R execute(IgfsTask<T, R> task, @Nullable IgfsRecordResolver rslvr,
        Collection<IgfsPath> paths, @Nullable T arg) {
        try {
            return executeAsync(task, rslvr, paths, arg).get();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T, R> R execute(IgfsTask<T, R> task, @Nullable IgfsRecordResolver rslvr,
        Collection<IgfsPath> paths, boolean skipNonExistentFiles, long maxRangeLen, @Nullable T arg) {
        try {
            return executeAsync(task, rslvr, paths, skipNonExistentFiles, maxRangeLen, arg).get();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T, R> R execute(Class<? extends IgfsTask<T, R>> taskCls,
        @Nullable IgfsRecordResolver rslvr, Collection<IgfsPath> paths, @Nullable T arg) {
        try {
            return executeAsync(taskCls, rslvr, paths, arg).get();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T, R> R execute(Class<? extends IgfsTask<T, R>> taskCls,
        @Nullable IgfsRecordResolver rslvr, Collection<IgfsPath> paths, boolean skipNonExistentFiles,
        long maxRangeSize, @Nullable T arg) {
        try {
            return executeAsync(taskCls, rslvr, paths, skipNonExistentFiles, maxRangeSize, arg).get();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /**
     * Executes IGFS task asynchronously.
     *
     * @param task Task to execute.
     * @param rslvr Optional resolver to control split boundaries.
     * @param paths Collection of paths to be processed within this task.
     * @param arg Optional task argument.
     * @return Execution future.
     */
    <T, R> IgniteInternalFuture<R> executeAsync(IgfsTask<T, R> task, @Nullable IgfsRecordResolver rslvr,
        Collection<IgfsPath> paths, @Nullable T arg) {
        return executeAsync(task, rslvr, paths, true, cfg.getMaximumTaskRangeLength(), arg);
    }

    /**
     * Executes IGFS task with overridden maximum range length (see
     * {@link org.apache.ignite.configuration.IgfsConfiguration#getMaximumTaskRangeLength()} for more information).
     *
     * @param task Task to execute.
     * @param rslvr Optional resolver to control split boundaries.
     * @param paths Collection of paths to be processed within this task.
     * @param skipNonExistentFiles Whether to skip non existent files. If set to {@code true} non-existent files will
     *     be ignored. Otherwise an exception will be thrown.
     * @param maxRangeLen Optional maximum range length. If {@code 0}, then by default all consecutive
     *      IGFS blocks will be included.
     * @param arg Optional task argument.
     * @return Execution future.
     */
    <T, R> IgniteInternalFuture<R> executeAsync(IgfsTask<T, R> task, @Nullable IgfsRecordResolver rslvr,
        Collection<IgfsPath> paths, boolean skipNonExistentFiles, long maxRangeLen, @Nullable T arg) {
        return igfsCtx.kernalContext().task().execute(task, new IgfsTaskArgsImpl<>(cfg.getName(), paths, rslvr,
            skipNonExistentFiles, maxRangeLen, arg));
    }

    /**
     * Executes IGFS task asynchronously.
     *
     * @param taskCls Task class to execute.
     * @param rslvr Optional resolver to control split boundaries.
     * @param paths Collection of paths to be processed within this task.
     * @param arg Optional task argument.
     * @return Execution future.
     */
    <T, R> IgniteInternalFuture<R> executeAsync(Class<? extends IgfsTask<T, R>> taskCls,
        @Nullable IgfsRecordResolver rslvr, Collection<IgfsPath> paths, @Nullable T arg) {
        return executeAsync(taskCls, rslvr, paths, true, cfg.getMaximumTaskRangeLength(), arg);
    }

    /**
     * Executes IGFS task asynchronously with overridden maximum range length (see
     * {@link org.apache.ignite.configuration.IgfsConfiguration#getMaximumTaskRangeLength()} for more information).
     *
     * @param taskCls Task class to execute.
     * @param rslvr Optional resolver to control split boundaries.
     * @param paths Collection of paths to be processed within this task.
     * @param skipNonExistentFiles Whether to skip non existent files. If set to {@code true} non-existent files will
     *     be ignored. Otherwise an exception will be thrown.
     * @param maxRangeLen Maximum range length.
     * @param arg Optional task argument.
     * @return Execution future.
     */
    <T, R> IgniteInternalFuture<R> executeAsync(Class<? extends IgfsTask<T, R>> taskCls,
        @Nullable IgfsRecordResolver rslvr, Collection<IgfsPath> paths, boolean skipNonExistentFiles,
        long maxRangeLen, @Nullable T arg) {
        return igfsCtx.kernalContext().task().execute((Class<IgfsTask<T, R>>)taskCls,
            new IgfsTaskArgsImpl<>(cfg.getName(), paths, rslvr, skipNonExistentFiles, maxRangeLen, arg));
    }

    /** {@inheritDoc} */
    @Override public boolean evictExclude(IgfsPath path, boolean primary) {
        assert path != null;

        try {
            // Exclude all PRIMARY files + the ones specified in eviction policy as exclusions.
            return primary || evictPlc == null || evictPlc.exclude(path);
        }
        catch (IgniteCheckedException e) {
            LT.error(log, e, "Failed to check whether the path must be excluded from evictions: " + path);

            return false;
        }
    }

    /**
     * Resolve file info for the given path and the given mode.
     *
     * @param path Path.
     * @param mode Mode.
     * @return File info or {@code null} in case file is not found.
     * @throws IgniteCheckedException If failed.
     */
    private IgfsFileInfo resolveFileInfo(IgfsPath path, IgfsMode mode) throws IgniteCheckedException {
        assert path != null;
        assert mode != null;

        IgfsFileInfo info = null;

        switch (mode) {
            case PRIMARY:
                info = meta.info(meta.fileId(path));

                break;

            case DUAL_SYNC:
            case DUAL_ASYNC:
                info = meta.info(meta.fileId(path));

                if (info == null) {
                    IgfsFile status = secondaryFs.info(path);

                    if (status != null)
                        info = status.isDirectory() ? new IgfsFileInfo(true, status.properties()) :
                            new IgfsFileInfo(status.blockSize(), status.length(), null, null, false,
                            status.properties());
                }

                break;

            default:
                assert false : "Unknown mode: " + mode;
        }

        return info;
    }

    /** {@inheritDoc} */
    @Override public IgniteFs withAsync() {
        return new IgfsAsyncImpl(this);
    }

    /** {@inheritDoc} */
    @Override public boolean isAsync() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public <R> IgniteFuture<R> future() {
        throw new IllegalStateException("Asynchronous mode is not enabled.");
    }

    /** Detailed file descriptor. */
    private static final class FileDescriptor {
        /** Parent file ID. */
        @Nullable
        private final IgniteUuid parentId;

        /** File name. */
        private final String fileName;

        /** File ID. */
        private final IgniteUuid fileId;

        /** File is plain data file or directory. */
        private final boolean isFile;

        /**
         * Constructs detailed file descriptor.
         *
         * @param parentId Parent file ID.
         * @param fileName File name.
         * @param fileId File ID.
         * @param isFile {@code True} if file.
         */
        private FileDescriptor(@Nullable IgniteUuid parentId, String fileName, IgniteUuid fileId, boolean isFile) {
            assert fileName != null;

            this.parentId = parentId;
            this.fileName = fileName;

            this.fileId = fileId;
            this.isFile = isFile;
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = parentId != null ? parentId.hashCode() : 0;

            res = 31 * res + fileName.hashCode();
            res = 31 * res + fileId.hashCode();
            res = 31 * res + (isFile ? 1231 : 1237);

            return res;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (o == this)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            FileDescriptor that = (FileDescriptor)o;

            return fileId.equals(that.fileId) && isFile == that.isFile && fileName.equals(that.fileName) &&
                (parentId == null ? that.parentId == null : parentId.equals(that.parentId));
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(FileDescriptor.class, this);
        }
    }

    /**
     * IGFS output stream extension that fires events.
     */
    private class IgfsEventAwareOutputStream extends IgfsOutputStreamImpl {
        /** Close guard. */
        private final AtomicBoolean closeGuard = new AtomicBoolean(false);

        /**
         * Constructs file output stream.
         *
         * @param path Path to stored file.
         * @param fileInfo File info.
         * @param parentId Parent ID.
         * @param bufSize The size of the buffer to be used.
         * @param mode IGFS mode.
         * @param batch Optional secondary file system batch.
         * @throws IgniteCheckedException In case of error.
         */
        IgfsEventAwareOutputStream(IgfsPath path, IgfsFileInfo fileInfo,
            IgniteUuid parentId, int bufSize, IgfsMode mode, @Nullable IgfsFileWorkerBatch batch)
            throws IgniteCheckedException {
            super(igfsCtx, path, fileInfo, parentId, bufSize, mode, batch, metrics);

            metrics.incrementFilesOpenedForWrite();
        }

        /** {@inheritDoc} */
        @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod")
        @Override protected void onClose() throws IOException {
            if (closeGuard.compareAndSet(false, true)) {
                super.onClose();

                metrics.decrementFilesOpenedForWrite();

                if (evts.isRecordable(EVT_IGFS_FILE_CLOSED_WRITE))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_CLOSED_WRITE, bytes()));
            }
        }
    }

    /**
     * IGFS input stream extension that fires events.
     */
    private class IgfsEventAwareInputStream extends IgfsInputStreamImpl {
        /** Close guard. */
        private final AtomicBoolean closeGuard = new AtomicBoolean(false);

        /**
         * Constructor.
         *
         * @param igfsCtx IGFS context.
         * @param path Path to stored file.
         * @param fileInfo File info.
         * @param prefetchBlocks Prefetch blocks.
         * @param seqReadsBeforePrefetch Amount of sequential reads before prefetch is triggered.
         * @param secReader Optional secondary file system reader.
         * @param metrics Metrics.
         */
        IgfsEventAwareInputStream(IgfsContext igfsCtx, IgfsPath path, IgfsFileInfo fileInfo,
            int prefetchBlocks, int seqReadsBeforePrefetch, @Nullable IgfsReader secReader,
            IgfsLocalMetrics metrics) {
            super(igfsCtx, path, fileInfo, prefetchBlocks, seqReadsBeforePrefetch, secReader, metrics);

            metrics.incrementFilesOpenedForRead();
        }

        /** {@inheritDoc} */
        @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod")
        @Override public void close() throws IOException {
            if (closeGuard.compareAndSet(false, true)) {
                super.close();

                metrics.decrementFilesOpenedForRead();

                if (evts.isRecordable(EVT_IGFS_FILE_CLOSED_READ))
                    evts.record(new IgfsEvent(path, localNode(), EVT_IGFS_FILE_CLOSED_READ, bytes()));
            }
        }
    }

    /**
     * Space calculation task.
     */
    @GridInternal
    private static class IgfsGlobalSpaceTask extends ComputeTaskSplitAdapter<Object, IgniteBiTuple<Long, Long>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** IGFS name. */
        private String igfsName;

        /**
         * @param igfsName IGFS name.
         */
        private IgfsGlobalSpaceTask(@Nullable String igfsName) {
            this.igfsName = igfsName;
        }

        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, Object arg) {
            Collection<ComputeJob> res = new ArrayList<>(gridSize);

            for (int i = 0; i < gridSize; i++) {
                res.add(new ComputeJobAdapter() {
                    /** Injected grid. */
                    @IgniteInstanceResource
                    private Ignite g;

                    @Nullable @Override public IgniteBiTuple<Long, Long> execute() {
                        IgniteFs igfs = ((IgniteKernal)g).context().igfs().igfs(igfsName);

                        if (igfs == null)
                            return F.t(0L, 0L);

                        IgfsMetrics metrics = igfs.metrics();

                        long loc = metrics.localSpaceSize();

                        return F.t(loc, metrics.maxSpaceSize());
                    }
                });
            }

            return res;
        }

        /** {@inheritDoc} */
        @Nullable @Override public IgniteBiTuple<Long, Long> reduce(List<ComputeJobResult> results) {
            long used = 0;
            long max = 0;

            for (ComputeJobResult res : results) {
                IgniteBiTuple<Long, Long> data = res.getData();

                if (data != null) {
                    used += data.get1();
                    max += data.get2();
                }
            }

            return F.t(used, max);
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) {
            // Never failover.
            return ComputeJobResultPolicy.WAIT;
        }
    }

    /**
     * Format message listener required for format action completion.
     */
    private class FormatMessageListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            if (msg instanceof IgfsDeleteMessage) {
                ClusterNode node = igfsCtx.kernalContext().discovery().node(nodeId);

                if (node != null) {
                    if (sameIgfs((IgfsAttributes[]) node.attribute(ATTR_IGFS))) {
                        IgfsDeleteMessage msg0 = (IgfsDeleteMessage)msg;

                        try {
                            msg0.finishUnmarshal(igfsCtx.kernalContext().config().getMarshaller(), null);
                        }
                        catch (IgniteCheckedException e) {
                            U.error(log, "Failed to unmarshal message (will ignore): " + msg0, e);

                            return;
                        }

                        assert msg0.id() != null;

                        GridFutureAdapter<?> fut = delFuts.remove(msg0.id());

                        if (fut != null) {
                            if (msg0.error() == null)
                                fut.onDone();
                            else
                                fut.onDone(msg0.error());
                        }
                    }
                }
            }
        }
    }

    /**
     * Discovery listener required for format actions completion.
     */
    private class FormatDiscoveryListener implements GridLocalEventListener {
        /** {@inheritDoc} */
        @Override public void onEvent(Event evt) {
            assert evt.type() == EVT_NODE_LEFT || evt.type() == EVT_NODE_FAILED;

            DiscoveryEvent evt0 = (DiscoveryEvent)evt;

            if (evt0.eventNode() != null) {
                if (sameIgfs((IgfsAttributes[]) evt0.eventNode().attribute(ATTR_IGFS))) {
                    Collection<IgniteUuid> rmv = new HashSet<>();

                    for (Map.Entry<IgniteUuid, GridFutureAdapter<Object>> fut : delFuts.entrySet()) {
                        IgniteUuid id = fut.getKey();

                        try {
                            if (!meta.exists(id)) {
                                fut.getValue().onDone();

                                rmv.add(id);
                            }
                        }
                        catch (IgniteCheckedException e) {
                            U.error(log, "Failed to check file existence: " + id, e);
                        }
                    }

                    for (IgniteUuid id : rmv)
                        delFuts.remove(id);
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteUuid nextAffinityKey() {
        if (enterBusy()) {
            try {
                return data.nextAffinityKey(null);
            }
            finally {
                busyLock.leaveBusy();
            }
        }
        else
            throw new IllegalStateException("Failed to get next affinity key because Grid is stopping.");
    }

    /** {@inheritDoc} */
    @Override public boolean isProxy(URI path) {
        IgfsMode mode = F.isEmpty(cfg.getPathModes()) ? cfg.getDefaultMode() :
            modeRslvr.resolveMode(new IgfsPath(path));

        return mode == PROXY;
    }
}
