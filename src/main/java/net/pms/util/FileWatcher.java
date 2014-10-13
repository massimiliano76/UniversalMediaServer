package net.pms.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.lang.ref.WeakReference;
import static java.nio.file.LinkOption.*;
import static java.nio.file.FileVisitOption.*;
import static java.nio.file.StandardWatchEventKinds.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An abstraction of the Java 7 nio WatchService api, which monitors native system
 * file-change notifications as opposed to directly polling or examining files.
 */

public class FileWatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger(FileWatcher.class);

	public static interface Listener {
		/**
		 * A user-defined callback for receiving file change notifications.
		 *
		 * @param filename The changed filepath, relative or absolute depending on the original filespec.
		 * @param event The change itself: 'ENTRY_CREATE' 'ENTRY_MODIFY' or 'ENTRY_DELETE'.
		 * @param watch The original user-supplied watch object that triggered the match.
		 * @param isDir Whether the changed file is a directory.
		 */
		public void notify(String filename, String event, FileWatcher.Watch watch, boolean isDir);
	}

	/**
	 * A file watchpoint.
	 */

	public static class Watch {
		public String fspec;
		private WeakReference<Listener> listener;
		private WeakReference<Object> item;
		public int flag;
		private PathMatcher matcher;

		// Convenience constructors

		public Watch(String fspec, Listener listener) {
			this(fspec, listener, null, 0);
		}

		public Watch(String fspec, Listener listener, Object item) {
			this(fspec, listener, item, 0);
		}

		public Watch(String fspec, Listener listener, int flag) {
			this(fspec, listener, null, flag);
		}

		/**
		 * Creates a file watchpoint.
		 *
		 * @param fspec The filespec describing what files to match. There are 2 patterns:
		 *              - glob (default, forward slashes work in Windows too):
		 *                  foo/bar.jpg      - a specific file.
		 *                  foo/*            - any file in the foo directory.
		 *                  foo/bar/*.jpg    - any jpg in the foo/bar directory.
		 *                  foo/**.png       - any png in the foo directory or below.
		 *                  foo/*.{png,jpg}  - any png or jpg in the foo directory.
		 *              - regex (must be prefixed with 'regex:'):
		 *                  TODO - presumably any regex string.
		 *              For more on syntax see {@link java.nio.file.FileSystem#getPathMatcher(String)}.
		 * @param listener The user-defined callback.**
		 * @param item A user Object to attach to this watchpoint.**
		 * @param flag A user constant to attach to this watchpoint.
		 *
		 * @implNote ** Note that {@code listener} and {@code item} are held as weak references
		 *    and will not persist if anonymously inlined in the constructor call.
		 */
		public Watch(String fspec, Listener listener, Object item, int flag) {
			// Make sure we have double-backslashes in Windows paths
			this.fspec = fspec.replace("\\\\", "\\").replace("\\", "\\\\");
			this.listener = new WeakReference<Listener>(listener);
			this.item = (item != null) ? new WeakReference<Object>(item) : null;
			this.flag = flag;
		}

		public void init(Path dir) {
			// Assume glob pattern if no prefix
			String match = (fspec.startsWith("glob:") || fspec.startsWith("regex:")) ? fspec : ("glob:" + fspec);
			matcher = dir.getFileSystem().getPathMatcher(match);
		}

		public Object getItem() {
			return (item != null) ? item.get() : null;
		}

		@Override
		public boolean equals(Object o) {
			if (o == null || ! (o instanceof Watch)) {
				return false;
			}
			Watch other = (Watch)o;
			return listener.get() == other.listener.get() &&
				(fspec == other.fspec || fspec.equals(other.fspec)) &&
				(item.get() == other.item.get() || item.get().equals(other.item.get())) &&
				flag == other.flag;
		}

		@Override
		public int hashCode() {
			return fspec.hashCode() + listener.hashCode();
		}

		public static boolean isRecursive(Watch w) {
			// FIXME: How to detect recursion in 'regex:' syntax?
			return w.fspec.contains("**") && ! w.fspec.startsWith("regex:");
		}

		public static boolean isValid(Watch w) {
			// Not valid if either the listener or item no longer exist
			return w.listener.get() != null || (w.item != null && w.item.get() == null);
		}
	}

	/**
	 * Add a file watchpoint to the Watch Service.
	 *
	 * @param w The watch object.
	 */
	public static void add(Watch w) {
		Path dir = Paths.get(FilenameUtils.getFullPath(w.fspec));
		w.init(dir);
		if (keys.contains(w)) {
			// Ignore duplicates
			return;
		}
		if (Watch.isRecursive(w)) {
			addRecursive(w, dir);
		} else {
			add(w, dir);
		}
	}

	/**
	 * Remove a file watchpoint from the Watch Service.
	 *
	 * @param w The watch object.
	 */
	public static boolean remove(Watch w) {
		return keys.remove(w);
	}

	// Internals

	/**
	 * A map of file watchpoints by watchkey.
	 */
	static class WatchMap extends HashMap<WatchKey, ArrayList<Watch>> {
		public void put(WatchKey k, Watch w) {
			if (! containsKey(k)) {
				put(k, new ArrayList<Watch>());
			}
			get(k).add(w);
		}

		public boolean contains(Watch w) {
			for (ArrayList<Watch> a : values()) {
				if (a.contains(w)) {
					return true;
				}
			}
			return false;
		}

		public boolean remove(Watch w) {
			for (WatchKey k : keySet()) {
				ArrayList<Watch> a = get(k);
				if (a.contains(w)) {
					return a.remove(w);
				}
			}
			return false;
		}
	}

	private static WatchMap keys = new WatchMap();
	private static WatchService watchService = null;

	public static void add(Watch w, Path dir) {
		if (watchService == null) {
			start(dir);
		}
		try {
			WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
			keys.put(key, w);
			LOGGER.debug("Added file watch at {}: {}", dir, w.fspec);
		} catch (Exception e) {
			LOGGER.debug("Register error: " + e);
			e.printStackTrace();
		}
	}

	public static void addRecursive(final Watch w, Path dir) {
		try {
			Files.walkFileTree(dir, EnumSet.of(FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					add(w, dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (Exception e) {
			LOGGER.debug("Recursion error: " + e);
			e.printStackTrace();
		}

	}

	private static void start(Path dir) {
		// Start the service
		try {
			watchService = dir.getFileSystem().newWatchService();
		} catch (Exception e) {
			LOGGER.debug("Error creating WatchService: " + e);
			e.printStackTrace();
		}

		// Watch for subscribed file events
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					do {
						// take() will block until events occur in our subscribed directories
						WatchKey key = watchService.take();
						try {
							// Wait a bit in case there are a few repeats
							Thread.sleep(100);
						} catch (InterruptedException e) { }
						// Filter the received directory event(s)
						for (WatchEvent<?> e : key.pollEvents()) {
							final WatchEvent.Kind<?> kind = e.kind();
							if (kind != OVERFLOW) {
								WatchEvent<Path> event = (WatchEvent<Path>)e;
								// Determine the actual file
								Path dir = (Path)key.watchable();
								final Path filename = dir.resolve(event.context());
								final boolean isDir = Files.isDirectory(filename/*, NOFOLLOW_LINKS*/);
								// See if we're watching for this specific file
								for (Iterator<Watch> iterator = keys.get(key).iterator(); iterator.hasNext();) {
									final Watch w = iterator.next();
									if (! Watch.isValid(w)) {
										LOGGER.debug("Deleting expired file watch at {}: {}", dir, w.fspec);
										iterator.remove();
										continue;
									}
									if (w.matcher.matches(filename)) {
										// We have an event of interest
										LOGGER.debug("{} (ct={}): {}", kind, event.count(), filename);
										if (isDir && kind == ENTRY_CREATE && Watch.isRecursive(w)) {
											// It's a new directory in a recursive scope,
											// traverse it to include any subdirs
											addRecursive(w, filename);
										} else {
											// It's a regular event, notify in a new thread
											// in case we trigger time-consuming activities
											new Thread(new Runnable() {
												@Override
												public void run() {
													w.listener.get().notify(filename.toString(), kind.toString(), w, isDir);
												}
											}, "File event").start();
										}
									}
								}
							}
						}
						// Reset and clean up
						if (! key.reset()) {
							keys.remove(key);
						}
					} while (! keys.isEmpty());
				} catch (Exception e) {
					LOGGER.debug("Event process error: " + e);
					e.printStackTrace();
				}
			}
		}, "File watcher").start();
	}
}

