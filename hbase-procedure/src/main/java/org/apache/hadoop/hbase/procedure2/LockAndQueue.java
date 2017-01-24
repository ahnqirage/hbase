/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.procedure2;

/**
 * Locking for mutual exclusion between procedures. Only by procedure framework internally.
 * {@link LockAndQueue} has two purposes:
 * <ol>
 *   <li>Acquire/release exclusive/shared locks</li>
 *   <li>Maintain a list of procedures waiting for this lock<br>
 *      To do so, {@link LockAndQueue} extends {@link ProcedureDeque} class. Using inheritance over
 *      composition for this need is unusual, but the choice is motivated by million regions
 *      assignment case as it will reduce memory footprint and number of objects to be GCed.
 * </ol>
 *
 * NOT thread-safe. Needs external concurrency control. For eg. Uses in MasterProcedureScheduler are
 * guarded by schedLock().
 * <br>
 * There is no need of 'volatile' keyword for member variables because of memory synchronization
 * guarantees of locks (see 'Memory Synchronization',
 * http://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Lock.html)
 * <br>
 * We do not implement Lock interface because we need exclusive + shared locking, and also
 * because try-lock functions require procedure id.
 * <br>
 * We do not use ReentrantReadWriteLock directly because of its high memory overhead.
 */
public class LockAndQueue extends ProcedureDeque implements LockStatus {
  private long exclusiveLockProcIdOwner = Long.MIN_VALUE;
  private int sharedLock = 0;

  // ======================================================================
  //  Lock Status
  // ======================================================================

  @Override
  public boolean isLocked() {
    return hasExclusiveLock() || sharedLock > 0;
  }

  @Override
  public boolean hasExclusiveLock() {
    return this.exclusiveLockProcIdOwner != Long.MIN_VALUE;
  }

  @Override
  public boolean isLockOwner(long procId) {
    return exclusiveLockProcIdOwner == procId;
  }

  @Override
  public boolean hasParentLock(final Procedure proc) {
    return proc.hasParent() && (isLockOwner(proc.getParentProcId()) || isLockOwner(proc.getRootProcId()));
  }

  @Override
  public boolean hasLockAccess(final Procedure proc) {
    return isLockOwner(proc.getProcId()) || hasParentLock(proc);
  }

  @Override
  public long getExclusiveLockProcIdOwner() {
    return exclusiveLockProcIdOwner;
  }

  @Override
  public int getSharedLockCount() {
    return sharedLock;
  }

  // ======================================================================
  //  try/release Shared/Exclusive lock
  // ======================================================================

  public boolean trySharedLock() {
    if (hasExclusiveLock()) return false;
    sharedLock++;
    return true;
  }

  public boolean releaseSharedLock() {
    return --sharedLock == 0;
  }

  public boolean tryExclusiveLock(final Procedure proc) {
    if (isLocked()) return hasLockAccess(proc);
    exclusiveLockProcIdOwner = proc.getProcId();
    return true;
  }

  public boolean releaseExclusiveLock(final Procedure proc) {
    if (isLockOwner(proc.getProcId())) {
      exclusiveLockProcIdOwner = Long.MIN_VALUE;
      return true;
    }
    return false;
  }
}
