/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.layout.impl;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.KijiIOException;
import org.kiji.schema.RuntimeInterruptedException;
import org.kiji.schema.util.ReferenceCountable;
import org.kiji.schema.util.Time;

/**
 * ZooKeeper client interface.
 */
@ApiAudience.Private
public class ZooKeeperClient implements ReferenceCountable<ZooKeeperClient> {
  private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperClient.class);

  /** Empty byte array used to create ZooKeeper "directory" nodes. */
  private static final byte[] EMPTY_BYTES = new byte[0];

  /** Time interval, in seconds, between ZooKeeper retries on error. */
  private static final double ZOOKEEPER_RETRY_DELAY = 1.0;

  // -----------------------------------------------------------------------------------------------

  /**
   * Watcher for the ZooKeeper session.
   *
   * <p> Processes notifications related to the liveness of the ZooKeeper session. </p>
   */
  private class SessionWatcher implements Watcher {
    /** {@inheritDoc} */
    @Override
    public void process(WatchedEvent event) {
      LOG.debug("ZooKeeper client: process({})", event);

      if (event.getType() != Event.EventType.None) {
        LOG.error("Unexpected event: {}", event);
        return;
      }
      if (event.getPath() != null) {
        LOG.error("Unexpected event: {}", event);
        return;
      }

      final State state = mState.get();

      switch (event.getState()) {
      case SyncConnected: {
        synchronized (ZooKeeperClient.this) {
          if (state != State.OPEN) {
            LOG.debug("ZooKeeper client session alive notification received while in state {}.",
                state);
          } else {
            LOG.debug("ZooKeeper client session {} alive.", mZKClient.getSessionId());
          }
          ZooKeeperClient.this.notifyAll();
        }
        break;
      }
      case AuthFailed:
      case ConnectedReadOnly:
      case SaslAuthenticated: {
        LOG.error("Error establishing ZooKeeper client session: {}", event);
        // Nothing to do here.
        break;
      }
      case Disconnected:
      case Expired: {
        synchronized (ZooKeeperClient.this) {
          LOG.debug("ZooKeeper client session {} died.", mZKClient.getSessionId());
          if (state == State.OPEN) {
            createZKClient();
          } else {
            LOG.debug("ZooKeeperClient in state {}; not reopening ZooKeeper session.", state);
          }
        }
        break;
      }
      default: {
        throw new RuntimeException("Unexpected ZooKeeper event state: " + event);
      }
      }
    }
  }

  // -----------------------------------------------------------------------------------------------

  /** Address of the ZooKeeper quorum to interact with. */
  private final String mZKAddress;

  /** Timeout for ZooKeeper sessions, in milliseconds. */
  private final int mSessionTimeoutMS;

  /** States of a ZooKeeper client instance. */
  private static enum State {
    UNINITIALIZED,
    INITIALIZED,
    OPEN,
    CLOSED
  }

  /** Tracks the state of this Zookeeper client. */
  private final AtomicReference<State> mState = new AtomicReference<State>(State.UNINITIALIZED);

  /** Represents the number of handles to this ZooKeeperClient. */
  private final AtomicInteger mRetainCount;

  /**
   * Current ZooKeeper session client.
   *
   * <ul>
   *   <li> Null before the client is opened and after the client is closed. </li>
   *   <li> Set when the client is opened, reset when the client dies. </li>
   * </ul>
   *
   * <p> Even when this ZooKeeper client session is non null, it might not be established yet or
   *   may be dead.
   * </p>
   */
  private ZooKeeper mZKClient = null;

  // -----------------------------------------------------------------------------------------------

  /**
   * Initializes a ZooKeeper client. The new ZooKeeperClient is returned with a retain count of one.
   *
   * @param zkAddress Address of the ZooKeeper quorum, as a comma-separated list of "host:port".
   * @param sessionTimeoutMS ZooKeeper session timeout, in milliseconds.
   *     If a session heart-beat fails for this much time, the ZooKeeper session is assumed dead.
   *     This is not a connection timeout.
   */
  public ZooKeeperClient(String zkAddress, int sessionTimeoutMS) {
    this.mZKAddress = zkAddress;
    this.mSessionTimeoutMS = sessionTimeoutMS;
    this.mRetainCount = new AtomicInteger(1);
    LOG.debug("Created {}", this);
    final State oldState = mState.getAndSet(State.INITIALIZED);
    Preconditions.checkState(oldState == State.UNINITIALIZED,
        "Cannot create SchemaTable instance in state %s.", oldState);  }

  /**
   * Starts the ZooKeeper client.
   */
  public void open() {
    final State oldState = mState.getAndSet(State.OPEN);
    Preconditions.checkState(oldState == State.INITIALIZED,
        "Cannot open ZooKeeperClient in state %s.", oldState);
    Preconditions.checkState(mZKClient == null);
    createZKClient();
  }

  /**
   * Returns whether this ZooKeeperClient has been opened and not closed.
   *
   * @return whether this ZooKeeperClient has been opened and not closed.
   */
  public boolean isOpen() {
    return mState.get() == State.OPEN;
  }

  /**
   * Factory for ZooKeeper session clients.
   *
   * <p> This returns immediately, but the ZooKeeper session is established asynchronously. </p>
   *
   * @throws KijiIOException on I/O error.
   */
  private synchronized void createZKClient() {
    try {
      LOG.debug("Creating new ZooKeeper client for address {} in {}", mZKAddress, this);
      mZKClient = new ZooKeeper(mZKAddress, mSessionTimeoutMS, new SessionWatcher());
    } catch (IOException ioe) {
      throw new KijiIOException(ioe);
    }
  }

  /**
   * Reports the ZooKeeper session client.
   *
   * @param timeout If no ZooKeeper session may be established within the specified timeout,
   *     in seconds, this fails and returns null.
   *     0 or negative means no timeout, ie. potentially wait forever.
   * @return A live ZooKeeper session client, or null.
   */
  public ZooKeeper getZKClient(double timeout) {

    // Absolute deadline, in seconds since the Epoch:
    final double absoluteDeadline = (timeout > 0) ? (Time.now() + timeout) : 0.0;
    State state;

    synchronized (this) {
      while (true) {
        state = mState.get();
        Preconditions.checkState(state == State.OPEN,
            "Cannot get ZKClient while ZookeeperClient is in state %s.", state);
        if ((mZKClient != null) && mZKClient.getState().isAlive()) {
          return mZKClient;
        } else {
          try {
            if (absoluteDeadline > 0) {
              final double waitTimeout = absoluteDeadline - Time.now();  // seconds
              this.wait((long)(waitTimeout * 1000.0));
            } else {
              this.wait();
            }
          } catch (InterruptedException ie) {
            throw new RuntimeInterruptedException(ie);
          }
        }
      }
    }
  }

  /**
   * Reports the ZooKeeper session client.
   *
   * <p> This may potentially wait forever: no timeout. </p>
   *
   * @return the ZooKeeper session client.
   */
  public ZooKeeper getZKClient() {
    return getZKClient(0.0);
  }

  /** {@inheritDoc} */
  @Override
  public ZooKeeperClient retain() {
    LOG.debug("Retaining {}", this);
    final int counter = mRetainCount.getAndIncrement();
    Preconditions.checkState(counter >= 1,
        "Cannot retain closed ZooKeeperClient: %s retain counter was %s.",
        toString(), counter);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public void release() throws IOException {
    LOG.debug("Releasing {}", this);
    final int counter = mRetainCount.decrementAndGet();
    Preconditions.checkState(counter >= 0,
        "Cannot release closed ZooKeeperClient: %s retain counter is now %s.",
        toString(), counter);
    if (counter == 0) {
      close();
    }
  }

  /**
   * Closes this ZooKeeper client.  Should only be called by {@link #release()} or finalize if not
   * released properly.
   */
  private void close() {
    LOG.debug("Closing {}", this);
    final State oldState = mState.getAndSet(State.CLOSED);
    Preconditions.checkState(oldState == State.OPEN,
        "Cannot close ZookeeperClient in state %s.", oldState);

    synchronized (this) {
      if (mZKClient == null) {
        // Nothing to close:
        return;
      }
      try {
        mZKClient.close();
        mZKClient = null;
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      }
    }
  }

  /**
   * See {@link ZooKeeper#create(String, byte[], List, CreateMode)}.
   *
   * @param path to desired node.
   * @param data the desired node should contain by default.
   * @param acl is the Access Control List the desired node will use.
   * @param createMode specifies whether the node to be created is ephemeral and/or sequential.
   * @return the actual path of the created node.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public File create(
      File path,
      byte[] data,
      List<ACL> acl,
      CreateMode createMode)
      throws KeeperException {

    final String unixPath = unixPath(path);
    while (true) {
      try {
        return new File(getZKClient().create(unixPath, data, acl, createMode));
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying create({}, {}, {}, {}).",
          unixPath, Bytes.toStringBinary(data), acl, createMode);
    }
  }

  /**
   * See {@link ZooKeeper#exists(String, boolean)}.
   *
   * @param path of a node.
   * @return the stat of the node; null if the node does not exist.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public Stat exists(File path) throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        return getZKClient().exists(unixPath, false);
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying exists({}).", unixPath);
    }
  }

  /**
   * See {@link ZooKeeper#exists(String, Watcher)}.
   *
   * @param path of a node.
   * @param watcher triggered by a successful operation that sets data on the node or
   *     creates/deletes the node.
   * @return the stat of the node.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public Stat exists(File path, Watcher watcher) throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        return getZKClient().exists(unixPath, watcher);
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying exists({}).", unixPath);
    }
  }

  /**
   * See {@link ZooKeeper#getData(String, Watcher, Stat)}.
   *
   * @param path of a node.
   * @param watcher triggered by a successful operation that sets data on the node or
   *     deletes the node.
   * @param stat of the node.
   * @return the data contained within the node.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public byte[] getData(File path, Watcher watcher, Stat stat)
      throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        return getZKClient().getData(unixPath, watcher, stat);
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying getData({}).", unixPath);
    }
  }

  /**
   * See {@link ZooKeeper#setData(String, byte[], int)}.
   *
   * @param path of a node.
   * @param data to set.
   * @param version of the node.
   * @return the stat of the node.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public Stat setData(File path, byte[] data, int version) throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        return getZKClient().setData(unixPath, data, version);
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
        LOG.debug("Retrying setData({}, {}, {}).", unixPath, Bytes.toStringBinary(data), version);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
    }
  }

  /**
   * See {@link ZooKeeper#getChildren(String, Watcher, Stat)}.
   *
   * @param path of a parent node.
   * @param watcher to trigger upon parent node deletion or creation/deletion of a child node.
   * @param stat of the provided parent node.
   * @return an unordered array of the parent node's children.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public List<String> getChildren(File path, Watcher watcher, Stat stat)
      throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        return getZKClient().getChildren(unixPath, watcher, stat);
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying getChildren({}).", unixPath);
    }
  }

  /**
   * See {@link ZooKeeper#delete(String, int)}.
   *
   * @param path of the node to delete.
   * @param version of the node to delete.
   * @throws KeeperException on ZooKeeper errors.
   *     Connection related errors are handled by retrying the operations.
   */
  public void delete(File path, int version) throws KeeperException {
    final String unixPath = unixPath(path);

    while (true) {
      try {
        getZKClient().delete(unixPath, version);
        return;
      } catch (InterruptedException ie) {
        throw new RuntimeInterruptedException(ie);
      } catch (ConnectionLossException ke) {
        LOG.debug("ZooKeeper connection lost.", ke);
      } catch (SessionExpiredException see) {
        LOG.debug("ZooKeeper session expired.", see);
      } catch (SessionMovedException sme) {
        LOG.debug("ZooKeeper session moved.", sme);
      }
      Time.sleep(ZOOKEEPER_RETRY_DELAY);
      LOG.debug("Retrying delete({}, {}).", unixPath, version);
    }
  }

  /**
   * Creates a ZooKeeper node and all its parents, if necessary.
   *
   * @param path of the node to create.
   * @throws KeeperException on I/O error.
   */
  public void createNodeRecursively(File path)
      throws KeeperException {
    final String unixPath = unixPath(path);

    if (exists(path) != null) {
      return;
    }

    if (unixPath.equals("/")) {
      // No need to create the root node "/" :
      return;
    }
    final File parent = path.getParentFile();
    if (parent != null) {
      createNodeRecursively(parent);
    }
    while (true) {
      try {
        LOG.debug("Creating ZooKeeper node: {}", unixPath);
        final File createdPath =
            this.create(path, EMPTY_BYTES, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        final String createdPathString = unixPath(createdPath);
        Preconditions.checkState(createdPathString.equals(unixPath));
        return;
      } catch (NodeExistsException exn) {
        LOG.debug("ZooKeeper node already exists: {}", unixPath);
        return;
      }
    }
  }

  /**
   * Returns a string representation of this object.
   * @return A string representation of this object.
   */
  public String toString() {
    return Objects.toStringHelper(getClass())
        .add("this", System.identityHashCode(this))
        .add("ZooKeeper_address", mZKAddress)
        .add("Session_timeout_millis", mSessionTimeoutMS)
        .add("Retain_count", mRetainCount.get())
        .toString();
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() throws Throwable {
    final State state = mState.get();
    if (state != State.CLOSED) {
      LOG.warn("Finalizing unreleased ZooKeeperClient in state {} with retain count {}.",
          state, mRetainCount.get());
      close();
    }
    super.finalize();
  }

  /**
   * Returns a path string in Unix format (slash as path separator).
   * @param path File instance to convert.
   * @return String representing file path as in Unix.
   */
  private static String unixPath(File path) {
    return FilenameUtils.separatorsToUnix(path.toString());
  }
}
