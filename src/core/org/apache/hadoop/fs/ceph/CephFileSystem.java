// -*- mode:Java; tab-width:2; c-basic-offset:2; indent-tabs-mode:t -*-

/**
 *
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 *
 * Implements the Hadoop FS interfaces to allow applications to store
 * files in Ceph.
 */
package org.apache.hadoop.fs.ceph;


import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.URI;
import java.net.InetAddress;
import java.util.EnumSet;
import java.lang.Math;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.net.DNS;

import com.ceph.fs.CephFileAlreadyExistsException;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;


/**
 * <p>
 * A {@link FileSystem} backed by <a href="http://ceph.newdream.net">Ceph.</a>.
 * This will not start a Ceph instance; one must already be running.
 * </p>
 * Configuration of the CephFileSystem is handled via a few Hadoop
 * Configuration properties: <br>
 * fs.ceph.monAddr -- the ip address/port of the monitor to connect to. <br>
 * fs.ceph.libDir -- the directory that libcephfs and libhadoopceph are
 * located in. This assumes Hadoop is being run on a linux-style machine
 * with names like libcephfs.so.
 * fs.ceph.commandLine -- if you prefer you can fill in this property
 * just as you would when starting Ceph up from the command line. Specific
 * properties override any configuration specified here.
 * <p>
 * You can also enable debugging of the CephFileSystem and Ceph itself: <br>
 * fs.ceph.debug -- if 'true' will print out method enter/exit messages,
 * plus a little more.
 * fs.ceph.clientDebug/fs.ceph.messengerDebug -- will print out debugging
 * from the respective Ceph system of at least that importance.
 */
public class CephFileSystem extends FileSystem {
  private static final Log LOG = LogFactory.getLog(CephFileSystem.class);
  private URI uri;

  private Path workingDir;
  private CephFS ceph = null;

  /**
   * Create a new CephFileSystem.
   */
  public CephFileSystem() {
  }

  /**
   * Used for testing purposes, this constructor
   * sets the given CephFS instead of defaulting to a
   * CephTalker (with its assumed real Ceph instance to talk to).
   *
   * TODO: make package private
   */
  public CephFileSystem(CephFS ceph_fs) {
    super();
    ceph = ceph_fs;
  }

  /**
   * Lets you get the URI of this CephFileSystem.
   * @return the URI.
   */
  public URI getUri() {
    LOG.debug("getUri:exit with return " + uri);
    return uri;
  }

  /** {@inheritDoc} */
  @Override
  public void initialize(URI uri, Configuration conf) throws IOException {
    super.initialize(uri, conf);
    if (ceph == null) {
      ceph = new CephTalker(conf, LOG);
    }
    ceph.initialize(uri, conf);
    setConf(conf);
    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
    this.workingDir = getHomeDirectory();
  }

  /**
   * Open a Ceph file and attach the file handle to an FSDataInputStream.
   * @param path The file to open
   * @param bufferSize Ceph does internal buffering; but you can buffer in
   *   the Java code too if you like.
   * @return FSDataInputStream reading from the given path.
   * @throws IOException if the path DNE or is a
   * directory, or there is an error getting data to set up the FSDataInputStream.
   */
  public FSDataInputStream open(Path path, int bufferSize) throws IOException {
    path = makeAbsolute(path);

    int fd = ceph.open(path, CephMount.O_RDONLY, 0);

    /* get file size */
    CephStat stat = new CephStat();
    ceph.fstat(fd, stat);

    CephInputStream istream = new CephInputStream(getConf(), ceph, fd,
        stat.size, bufferSize);
    return new FSDataInputStream(istream);
  }

  /**
   * Close down the CephFileSystem. Runs the base-class close method
   * and then kills the Ceph client itself.
   */
  @Override
  public void close() throws IOException {
    super.close(); // this method does stuff, make sure it's run!
    ceph.shutdown();
  }

  /**
   * Get an FSDataOutputStream to append onto a file.
   * @param file The File you want to append onto
   * @param bufferSize Ceph does internal buffering but you can buffer in the Java code as well if you like.
   * @param progress The Progressable to report progress to.
   * Reporting is limited but exists.
   * @return An FSDataOutputStream that connects to the file on Ceph.
   * @throws IOException If the file cannot be found or appended to.
   */
  public FSDataOutputStream append(Path file, int bufferSize,
      Progressable progress) throws IOException {
    LOG.debug("append:enter with path " + file + " bufferSize " + bufferSize);
    Path abs_path = makeAbsolute(file);

    if (progress != null) {
      progress.progress();
    }
    LOG.trace("append: Entering ceph_open_for_append from Java");
    int flags = CephMount.O_WRONLY|CephMount.O_CREAT|CephMount.O_APPEND;
    int fd = ceph.open(abs_path, flags, 0);

    LOG.trace("append: Returned to Java");
    if (progress != null) {
      progress.progress();
    }
    if (fd < 0) { // error in open
      throw new IOException(
          "append: Open for append failed on path \"" + abs_path.toString()
          + "\"");
    }
    CephOutputStream cephOStream = new CephOutputStream(getConf(), ceph, fd,
        bufferSize);

    LOG.debug("append:exit");
    return new FSDataOutputStream(cephOStream, statistics);
  }

  /**
   * Get the current working directory for the given file system
   * @return the directory Path
   */
  public Path getWorkingDirectory() {
    return workingDir;
  }

  /**
   * Set the current working directory for the given file system. All relative
   * paths will be resolved relative to it.
   *
   * @param dir The directory to change to.
   */
  @Override
  public void setWorkingDirectory(Path dir) {
    workingDir = makeAbsolute(dir);
  }

  /**
   * Return only the path component from a potentially fully qualified path.
   */
  private String getCephPath(Path path) {
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Path must be absolute: " + path);
    }
    return path.toUri().getPath();
  }


  /**
   * Create a directory and any nonexistent parents. Any portion
   * of the directory tree can exist without error.
   * @param path The directory path to create
   * @param perms The permissions to apply to the created directories.
   * @return true if successful, false otherwise
   * @throws IOException if the path is a child of a file.
   */
  @Override
  public boolean mkdirs(Path path, FsPermission perms) throws IOException {
    LOG.debug("mkdirs:enter with path " + path);
    Path abs_path = makeAbsolute(path);

    LOG.trace("mkdirs:calling ceph_mkdirs from Java");
    int result = ceph.ceph_mkdirs(getCephPath(abs_path), (int) perms.toShort());

    if (result != 0) {
      LOG.warn(
          "mkdirs: make directory " + abs_path + "Failing with result " + result);
      if (-ceph.ENOTDIR == result) {
        throw new IOException("Parent path is not a directory");
      }
      return false;
    } else {
      LOG.debug("mkdirs:exiting succesfully");
      return true;
    }
  }

  /**
   * Get stat information on a file. This does not fill owner or group, as
   * Ceph's support for these is a bit different than HDFS'.
   * @param path The path to stat.
   * @return FileStatus object containing the stat information.
   * @throws FileNotFoundException if the path could not be resolved.
   */
  public FileStatus getFileStatus(Path path) throws IOException {
    path = makeAbsolute(path);

    CephStat stat = new CephStat();
    ceph.lstat(path, stat);

    FileStatus status = new FileStatus(stat.size, stat.isDir(),
          ceph.ceph_replication(path), stat.blksize, stat.m_time,
          stat.a_time, new FsPermission((short) stat.mode),
          System.getProperty("user.name"), null, path.makeQualified(this));

    return status;
  }

  /**
   * Get the FileStatus for each listing in a directory.
   * @param path The directory to get listings from.
   * @return FileStatus[] containing one FileStatus for each directory listing;
   *         null if path does not exist.
   */
  public FileStatus[] listStatus(Path path) throws IOException {
    path = makeAbsolute(path);

    String[] dirlist = ceph.listdir(path);
    if (dirlist != null) {
      FileStatus[] status = new FileStatus[dirlist.length];
      for (int i = 0; i < status.length; i++) {
        status[i] = getFileStatus(new Path(path, dirlist[i]));
      }
      return status;
    }

    if (isFile(path))
      return new FileStatus[] { getFileStatus(path) };

    return null;
  }

  /** {@inheritDocs} */
  @Override
  public void setPermission(Path path, FsPermission permission) throws IOException {
    path = makeAbsolute(path);
    ceph.chmod(path, permission.toShort());
  }

  /** {@inheritDocs} */
  @Override
  public void setTimes(Path path, long mtime, long atime) throws IOException {
    path = makeAbsolute(path);

    CephStat stat = new CephStat();
    int mask = 0;

    if (mtime != -1) {
      mask |= CephMount.SETATTR_MTIME;
      stat.m_time = mtime;
    }

    if (atime != -1) {
      mask |= CephMount.SETATTR_ATIME;
      stat.a_time = atime;
    }

    ceph.setattr(path, stat, mask);
  }

  /**
   * Create a new file and open an FSDataOutputStream that's connected to it.
   * @param path The file to create.
   * @param permission The permissions to apply to the file.
   * @param overwrite If true, overwrite any existing file with
	 * this name; otherwise don't.
   * @param bufferSize Ceph does internal buffering, but you can buffer
   *   in the Java code too if you like.
   * @param replication Ignored by Ceph. This can be
   * configured via Ceph configuration.
   * @param blockSize Ignored by Ceph. You can set client-wide block sizes
   * via the fs.ceph.blockSize param if you like.
   * @param progress A Progressable to report back to.
   * Reporting is limited but exists.
   * @return An FSDataOutputStream pointing to the created file.
   * @throws IOException if the path is an
   * existing directory, or the path exists but overwrite is false, or there is a
   * failure in attempting to open for append with Ceph.
   */
  public FSDataOutputStream create(Path path,
      FsPermission permission,
      boolean overwrite,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress) throws IOException {
    LOG.debug("create:enter with path " + path);
    Path abs_path = makeAbsolute(path);

    if (progress != null) {
      progress.progress();
    }
    // We ignore replication since that's not configurable here, and
    // progress reporting is quite limited.
    // Required semantics: if the file exists, overwrite if 'overwrite' is set;
    // otherwise, throw an exception

    // Step 1: existence test
    boolean exists = exists(abs_path);

    if (exists) {
      if (getFileStatus(abs_path).isDir()) {
        throw new IOException(
            "create: Cannot overwrite existing directory \"" + path.toString()
            + "\" with a file");
      }
      if (!overwrite) {
        throw new IOException(
            "createRaw: Cannot open existing file \"" + abs_path.toString()
            + "\" for writing without overwrite flag");
      }
    }

    if (progress != null) {
      progress.progress();
    }

    // Step 2: create any nonexistent directories in the path
    if (!exists) {
      Path parent = abs_path.getParent();

      if (parent != null) { // if parent is root, we're done
        try {
          ceph.ceph_mkdirs(getCephPath(parent), permission.toShort());
        } catch (CephFileAlreadyExistsException e) {}
      }
      if (progress != null) {
        progress.progress();
      }
    }

    // Step 3: open the file
    LOG.trace("calling ceph_open_for_overwrite from Java");
    int flags = CephMount.O_WRONLY|CephMount.O_CREAT|CephMount.O_TRUNC;
    int fh = ceph.open(abs_path, flags, (int)permission.toShort());

    if (progress != null) {
      progress.progress();
    }
    LOG.trace("Returned from ceph_open_for_overwrite to Java with fh " + fh);
    if (fh < 0) {
      throw new IOException(
          "create: Open for overwrite failed on path \"" + path.toString()
          + "\"");
    }

    // Step 4: create the stream
    OutputStream cephOStream = new CephOutputStream(getConf(), ceph, fh,
        bufferSize);

    LOG.debug("create:exit");
    return new FSDataOutputStream(cephOStream, statistics);
  }

  /**
   * Rename a file or directory.
   * @param src The current path of the file/directory
   * @param dst The new name for the path.
   * @return true if the rename succeeded, false otherwise.
   */
  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    src = makeAbsolute(src);
    dst = makeAbsolute(dst);

    try {
      CephStat stat = new CephStat();
      ceph.lstat(dst, stat);
      if (stat.isDir())
        return rename(src, new Path(dst, src.getName()));
      return false;
    } catch (FileNotFoundException e) {}

    try {
      ceph.rename(src, dst);
    } catch (FileNotFoundException e) {
      return false;
    }

    return true;
  }

  /**
   * Get a BlockLocation object for each block in a file.
   *
   * Note that this doesn't include port numbers in the name field as
   * Ceph handles slow/down servers internally. This data should be used
   * only for selecting which servers to run which jobs on.
   *
   * @param file A FileStatus object corresponding to the file you want locations for.
   * @param start The offset of the first part of the file you are interested in.
   * @param len The amount of the file past the offset you are interested in.
   * @return A BlockLocation[] where each object corresponds to a block within
   * the given range.
   */
  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus file, long start, long len) throws IOException {
    Path abs_path = makeAbsolute(file.getPath());

    int fh = ceph.open(abs_path, CephMount.O_RDONLY, 0);
    if (fh < 0) {
      LOG.error("getFileBlockLocations:got error " + fh + ", exiting and returning null!");
      return null;
    }

    long blockSize = ceph.ceph_getblocksize(getCephPath(abs_path));
    BlockLocation[] locations = new BlockLocation[(int) Math.ceil(len / (float) blockSize)];

    for (int i = 0; i < locations.length; ++i) {
      long offset = start + i * blockSize;
      long blockStart = start + i * blockSize - (start % blockSize);
      locations[i] = new BlockLocation(null, null, blockStart, blockSize);
      LOG.debug("getFileBlockLocations: location[" + i + "]: " + locations[i]);
    }

    ceph.close(fh);
    return locations;
  }

  @Deprecated
	public boolean delete(Path path) throws IOException {
		return delete(path, false);
	}

  /** {@inheritDoc} */
  public boolean delete(Path path, boolean recursive) throws IOException {
    path = makeAbsolute(path);

    /* path exists? */
    FileStatus status;
    try {
      status = getFileStatus(path);
    } catch (FileNotFoundException e) {
      return false;
    }

    /* we're done if its a file */
    if (!status.isDir()) {
      ceph.unlink(path);
      return true;
    }

    /* get directory contents */
    FileStatus[] dirlist = listStatus(path);
    if (dirlist == null)
      return false;

    if (!recursive && dirlist.length > 0)
      throw new IOException("Directory " + path.toString() + "is not empty.");

    for (FileStatus fs : dirlist) {
      if (!delete(fs.getPath(), recursive))
        return false;
    }

    ceph.rmdir(path);
    return true;
  }

  /**
   * Returns the default replication value of 1. This may
   * NOT be the actual value, as replication is controlled
   * by a separate Ceph configuration.
   */
  @Override
  public short getDefaultReplication() {
    return 1;
  }

  /**
   * Get the default block size.
   * @return the default block size, in bytes, as a long.
   */
  @Override
  public long getDefaultBlockSize() {
    return getConf().getInt("fs.ceph.blockSize", 1 << 26);
  }

  /**
   * Adds the working directory to path if path is not already
   * an absolute path. The URI scheme is not removed here. It
   * is removed only when users (e.g. ceph native calls) need
   * the path-only portion.
   */
  private Path makeAbsolute(Path path) {
    if (path.isAbsolute()) {
      return path;
    }
    return new Path(workingDir, path);
  }

}
