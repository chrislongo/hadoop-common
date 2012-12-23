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
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.net.DNS;

import com.ceph.fs.CephFileAlreadyExistsException;
import com.ceph.fs.CephNotDirectoryException;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;


/**
 * Known Issues:
 *
 *   1. Per-file replication and block size are ignored.
 */
public class CephFileSystem extends FileSystem {
  private static final Log LOG = LogFactory.getLog(CephFileSystem.class);
  private URI uri;

  private Path workingDir;
  private CephFS ceph;

  /**
   * Create a new CephFileSystem.
   */
  public CephFileSystem() {
  }

  /**
   * Create an absolute path using the working directory.
   */
  private Path makeAbsolute(Path path) {
    if (path.isAbsolute()) {
      return path;
    }
    return new Path(workingDir, path);
  }

  public URI getUri() {
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
   * @param path The File you want to append onto
   * @param bufferSize Ceph does internal buffering but you can buffer in the Java code as well if you like.
   * @param progress The Progressable to report progress to.
   * Reporting is limited but exists.
   * @return An FSDataOutputStream that connects to the file on Ceph.
   * @throws IOException If the file cannot be found or appended to.
   */
  public FSDataOutputStream append(Path path, int bufferSize,
      Progressable progress) throws IOException {
    path = makeAbsolute(path);

    if (progress != null) {
      progress.progress();
    }

    int fd = ceph.open(path, CephMount.O_WRONLY|CephMount.O_APPEND, 0);

    if (progress != null) {
      progress.progress();
    }

    CephOutputStream ostream = new CephOutputStream(getConf(), ceph, fd,
        bufferSize);
    return new FSDataOutputStream(ostream, statistics);
  }

  public Path getWorkingDirectory() {
    return workingDir;
  }

  @Override
  public void setWorkingDirectory(Path dir) {
    workingDir = makeAbsolute(dir);
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
    path = makeAbsolute(path);

    boolean result = false;
    try {
      ceph.mkdirs(path, (int) perms.toShort());
      result = true;
    } catch (CephFileAlreadyExistsException e) {
      result = true;
    }

    return result;
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
          ceph.get_file_replication(path), stat.blksize, stat.m_time,
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
  public FSDataOutputStream create(Path path, FsPermission permission,
      boolean overwrite, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {

    path = makeAbsolute(path);

    boolean exists = exists(path);

    if (progress != null) {
      progress.progress();
    }

    int flags = CephMount.O_WRONLY | CephMount.O_CREAT;

    if (exists) {
      if (overwrite)
        flags |= CephMount.O_TRUNC;
      else
        throw new FileAlreadyExistsException();
    } else {
      Path parent = path.getParent();
      if (parent != null)
        if (!mkdirs(parent, permission))
          throw new IOException("mkdirs failed for " + parent.toString());
    }

    if (progress != null) {
      progress.progress();
    }

    int fd = ceph.open(path, flags, (int)permission.toShort());

    if (progress != null) {
      progress.progress();
    }

    OutputStream ostream = new CephOutputStream(getConf(), ceph, fd,
        bufferSize);
    return new FSDataOutputStream(ostream, statistics);
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

    /* Get block size */
    CephStat stat = new CephStat();
    ceph.fstat(fh, stat);
    long blockSize = stat.blksize;

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

  @Override
  public short getDefaultReplication() {
    return ceph.getDefaultReplication();
  }

  @Override
  public long getDefaultBlockSize() {
    return getConf().getLong(
        CephConfigKeys.CEPH_BLOCK_SIZE_KEY,
        CephConfigKeys.CEPH_BLOCK_SIZE_DEFAULT);
  }

}
