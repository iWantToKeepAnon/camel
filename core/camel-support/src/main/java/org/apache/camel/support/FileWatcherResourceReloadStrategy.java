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
package org.apache.camel.support;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.api.management.ManagedAttribute;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.spi.Resource;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * A file based {@link org.apache.camel.spi.ResourceReloadStrategy} which watches a file folder for modified files and
 * reload on file changes.
 * <p/>
 * This implementation uses the JDK {@link WatchService} to watch for when files are created or modified. Mac OS X users
 * should be noted the osx JDK does not support native file system changes and therefore the watch service is much
 * slower than on Linux or Windows systems.
 */
@ManagedResource(description = "Managed FileWatcherResourceReloadStrategy")
public class FileWatcherResourceReloadStrategy extends ResourceReloadStrategySupport {

    private static final Logger LOG = LoggerFactory.getLogger(FileWatcherResourceReloadStrategy.class);

    private String folder;
    private boolean isRecursive;
    private WatchService watcher;
    private ExecutorService executorService;
    private WatchFileChangesTask task;
    private Map<WatchKey, Path> folderKeys;
    private long pollTimeout = 2000;
    private FileFilter fileFilter;

    public FileWatcherResourceReloadStrategy() {
        setRecursive(false);
    }

    public FileWatcherResourceReloadStrategy(String directory) {
        setFolder(directory);
        setRecursive(false);
    }

    public FileWatcherResourceReloadStrategy(String directory, boolean isRecursive) {
        setFolder(directory);
        setRecursive(isRecursive);
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public void setRecursive(boolean isRecursive) {
        this.isRecursive = isRecursive;
    }

    /**
     * Sets the poll timeout in millis. The default value is 2000.
     */
    public void setPollTimeout(long pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    @ManagedAttribute(description = "Folder being watched")
    public String getFolder() {
        return folder;
    }

    @ManagedAttribute(description = "Whether the reload strategy watches directory recursively")
    public boolean isRecursive() {
        return isRecursive;
    }

    @ManagedAttribute(description = "Whether the watcher is running")
    public boolean isRunning() {
        return task != null && task.isRunning();
    }

    public FileFilter getFileFilter() {
        return fileFilter;
    }

    /**
     * To use a custom filter for accepting files.
     */
    public void setFileFilter(FileFilter fileFilter) {
        this.fileFilter = fileFilter;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        if (folder == null) {
            // no folder configured
            return;
        }

        File dir = new File(folder);
        if (dir.exists() && dir.isDirectory()) {
            LOG.info("Starting ReloadStrategy to watch directory: {}", dir);

            WatchEvent.Modifier modifier = null;

            // if its mac OSX then attempt to apply workaround or warn its slower
            String os = ObjectHelper.getSystemProperty("os.name", "");
            if (os.toLowerCase(Locale.US).startsWith("mac")) {
                // this modifier can speedup the scanner on mac osx (as java on mac has no native file notification integration)
                Class<WatchEvent.Modifier> clazz = getCamelContext().getClassResolver()
                        .resolveClass("com.sun.nio.file.SensitivityWatchEventModifier", WatchEvent.Modifier.class);
                if (clazz != null) {
                    WatchEvent.Modifier[] modifiers = clazz.getEnumConstants();
                    for (WatchEvent.Modifier mod : modifiers) {
                        if ("HIGH".equals(mod.name())) {
                            modifier = mod;
                            break;
                        }
                    }
                }
                if (modifier != null) {
                    LOG.info(
                            "On Mac OS X the JDK WatchService is slow by default so enabling SensitivityWatchEventModifier.HIGH as workaround");
                } else {
                    LOG.warn(
                            "On Mac OS X the JDK WatchService is slow and it may take up till 10 seconds to notice file changes");
                }
            }

            try {
                Path path = dir.toPath();
                watcher = path.getFileSystem().newWatchService();
                // we cannot support deleting files as we don't know which routes that would be
                if (isRecursive) {
                    this.folderKeys = new HashMap<>();
                    registerRecursive(watcher, path, modifier);
                } else {
                    registerPathToWatcher(modifier, path, watcher);
                }

                task = new WatchFileChangesTask(watcher, path);

                executorService = getCamelContext().getExecutorServiceManager().newSingleThreadExecutor(this,
                        "FileWatcherReloadStrategy");
                executorService.submit(task);
            } catch (IOException e) {
                throw ObjectHelper.wrapRuntimeCamelException(e);
            }
        }
    }

    private WatchKey registerPathToWatcher(WatchEvent.Modifier modifier, Path path, WatchService watcher) throws IOException {
        WatchKey key;
        if (modifier != null) {
            key = path.register(watcher, new WatchEvent.Kind<?>[] { ENTRY_CREATE, ENTRY_MODIFY }, modifier);
        } else {
            key = path.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
        }
        return key;
    }

    private void registerRecursive(final WatchService watcher, final Path root, final WatchEvent.Modifier modifier)
            throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = registerPathToWatcher(modifier, dir, watcher);
                folderKeys.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();

        if (executorService != null) {
            getCamelContext().getExecutorServiceManager().shutdownGraceful(executorService);
            executorService = null;
        }

        if (watcher != null) {
            IOHelper.close(watcher);
        }
    }

    /**
     * Background task which watches for file changes
     */
    protected class WatchFileChangesTask implements Runnable {

        private final WatchService watcher;
        private final Path folder;
        private volatile boolean running;

        public WatchFileChangesTask(WatchService watcher, Path folder) {
            this.watcher = watcher;
            this.folder = folder;
        }

        public boolean isRunning() {
            return running;
        }

        public void run() {
            LOG.debug("FileReloadStrategy is starting watching folder: {}", folder);

            // allow running while starting Camel
            while (isStarting() || isRunAllowed()) {
                running = true;

                WatchKey key;
                try {
                    LOG.trace("FileReloadStrategy is polling for file changes in directory: {}", folder);
                    // wait for a key to be available
                    key = watcher.poll(pollTimeout, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    break;
                }

                if (key != null) {
                    Path pathToReload;
                    if (isRecursive) {
                        pathToReload = folderKeys.get(key);
                    } else {
                        pathToReload = folder;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent<Path> we = (WatchEvent<Path>) event;
                        Path path = we.context();
                        File file = pathToReload.resolve(path).toFile();
                        String name = FileUtil.compactPath(file.getPath());
                        LOG.debug("Detected Modified/Created file: {}", name);
                        boolean accept = fileFilter == null || fileFilter.accept(file);
                        if (accept) {
                            LOG.debug("Accepted Modified/Created file: {}", name);
                            try {
                                ExtendedCamelContext ecc = getCamelContext().adapt(ExtendedCamelContext.class);
                                // must use file resource loader as we cannot load from classpath
                                Resource resource = ecc.getResourceLoader().resolveResource("file:" + name);
                                getResourceReload().onReload(name, resource);
                                incSucceededCounter();
                            } catch (Exception e) {
                                incFailedCounter();
                                LOG.warn("Error reloading routes from file: " + name + " due " + e.getMessage()
                                         + ". This exception is ignored.",
                                        e);
                            }
                        }
                    }

                    // the key must be reset after processed
                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                }
            }

            running = false;

            LOG.debug("FileReloadStrategy is stopping watching folder: {}", folder);
        }
    }

}
