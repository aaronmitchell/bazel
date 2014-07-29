// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.util.GroupedList;
import com.google.devtools.build.lib.util.GroupedList.GroupedListHelper;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Data the ValueEntry uses to maintain its state before it is done building. It allows the
 * {@link ValueEntry} to keep the current state of the entry across invalidation and successive
 * evaluations. A done value does not contain any of this data. However, if a value is marked dirty,
 * its entry acquires a new {@code BuildingState} object, which persists until it is done again.
 *
 * <p>This class should be considered a private inner class of {@link ValueEntry} -- no other
 * classes should instantiate a {@code BuildingState} object or call any of its methods directly.
 * It is in a separate file solely to keep the {@code ValueEntry} class readable. In particular, the
 * caller must synchronize access to this class.
 */
@ThreadCompatible
final class BuildingState {
  enum DirtyState {
    /**
     * The value's dependencies need to be checked to see if it needs to be rebuilt. The
     * dependencies must be obtained through calls to {@link #getNextDirtyDirectDeps} and checked.
     */
    CHECK_DEPENDENCIES,
    /**
     * All of the value's dependencies are unchanged, and the value itself was not marked changed,
     * so its current value is still valid -- it need not be rebuilt.
     */
    VERIFIED_CLEAN,
    /**
     * A rebuilding is required or in progress, because either the value itself changed or one of
     * its dependencies did.
     */
    REBUILDING
  }

  /**
   * During its life, a value can go through states as follows:
   * <ol>
   * <li>Non-existent
   * <li>Just created ({@code evaluating} is false)
   * <li>Evaluating ({@code evaluating} is true)
   * <li>Done (meaning this buildingState object is null)
   * <li>Just created (when it is dirtied during evaluation)
   * <li>Reset (just before it is re-evaluated)
   * <li>Evaluating
   * <li>Done
   * </ol>
   *
   * <p>The "just created" state is there to allow the {@link EvaluableGraph#createIfAbsent} and
   * {@link ValueEntry#addReverseDepAndCheckIfDone} methods to be separate. All callers have to
   * call both methods in that order if they want to create a value. The second method calls
   * {@link #startEvaluating}, which transitions the current value to the "evaluating" state and
   * returns true only the first time it was called. A caller that gets "true" back from that call
   * must start the evaluation of this value, while any subsequent callers must not.
   *
   * <p>An entry is set to "evaluating" as soon as it is scheduled for evaluation. Thus, even a
   * value that is never actually built (for instance, a dirty value that is verified as clean) is
   * in the "evaluating" state until it is done.
   */
  private boolean evaluating = false;

  /**
   * The state of a dirty value. A value is marked dirty in the BuildingState constructor, and goes
   * into either the state {@link DirtyState#CHECK_DEPENDENCIES} or {@link DirtyState#REBUILDING},
   * depending on whether the caller specified that the value was itself changed or not. A non-null
   * {@code dirtyState} indicates that the value {@link #isDirty} in some way.
   */
  private DirtyState dirtyState = null;

  /**
   * The number of dependencies that are known to be done in a {@link ValueEntry}. There is a
   * potential check-then-act race here, so we need to make sure that when this is increased, we
   * always check if the new value is equal to the number of required dependencies, and if so, we
   * must re-schedule the value for evaluation.
   *
   * <p>There are two potential pitfalls here: 1) If multiple dependencies signal this value in
   * close succession, this value should be scheduled exactly once. 2) If a thread is still working
   * on this value, it should not be scheduled.
   *
   * <p>The first problem is solved by the {@link #signalDep} method, which also returns if the
   * value needs to be re-scheduled, and ensures that only one thread gets a true return value.
   *
   * <p>The second problem is solved by first adding the newly discovered deps to a value's
   * {@link #directDeps}, and then looping through the direct deps and registering this value as a
   * reverse dependency. This ensures that the signaledDeps counter can only reach
   * {@link #directDeps}.size() on the very last iteration of the loop, i.e., the thread is not
   * working on the value anymore. Note that this requires that there is no code after the loop in
   * {@code ParallelEvaluator.Evaluate#run}.
   */
  private int signaledDeps = 0;

  /**
   * Direct dependencies discovered during the build. They will be written to the immutable field
   * {@code ValueEntry#directDeps} and the dependency group data to {@code ValueEntry#groupData}
   * once the value is finished building. {@link SkyFunction}s can request deps in groups, and these
   * groupings are preserved in this field.
   */
  private final GroupedList<SkyKey> directDeps = new GroupedList<>();

  /**
   * The set of reverse dependencies that are registered before the value has finished building.
   * Upon building, these reverse deps will be signaled and then stored in the permanent
   * {@code ValueEntry#reverseDeps}.
   */
  // TODO(bazel-team): Remove this field. With eager invalidation, all direct deps on this dirty
  // value will be removed by the time evaluation starts, so reverse deps to signal can just be
  // reverse deps in the main ValueEntry object.
  private Object reverseDepsToSignal = ImmutableList.of();
  private List<SkyKey> reverseDepsToRemove = null;
  private boolean reverseDepIsSingleObject = false;

  private static final ReverseDepsUtil<BuildingState> REVERSE_DEPS_UTIL =
      new ReverseDepsUtil<BuildingState>() {
    @Override
    void setReverseDepsObject(BuildingState container, Object object) {
      container.reverseDepsToSignal = object;
    }

    @Override
    void setSingleReverseDep(BuildingState container, boolean singleObject) {
      container.reverseDepIsSingleObject = singleObject;
    }

    @Override
    void setReverseDepsToRemove(BuildingState container, List<SkyKey> object) {
      container.reverseDepsToRemove = object;
    }

    @Override
    Object getReverseDepsObject(BuildingState container) {
      return container.reverseDepsToSignal;
    }

    @Override
    boolean isSingleReverseDep(BuildingState container) {
      return container.reverseDepIsSingleObject;
    }

    @Override
    List<SkyKey> getReverseDepsToRemove(BuildingState container) {
      return container.reverseDepsToRemove;
    }
  };

  // Below are fields that are used for dirty values.

  /**
   * The dependencies requested (with group markers) last time the value was built (and below, the
   * value last time the value was built). They will be compared to dependencies requested on this
   * build to check whether this value has changed in {@link ValueEntry#setValue}. If they are null,
   * it means that this value is being built for the first time. See {@link #directDeps} for more on
   * dependency group storage.
   */
  private final GroupedList<SkyKey> lastBuildDirectDeps;
  private final SkyValue lastBuildValue;

  /**
   * Which child should be re-evaluated next in the process of determining if this entry needs to
   * be re-evaluated. Used by {@link #getNextDirtyDirectDeps} and {@link #signalDep(boolean)}.
   */
  private Iterator<Iterable<SkyKey>> dirtyDirectDepIterator = null;

  BuildingState() {
    lastBuildDirectDeps = null;
    lastBuildValue = null;
  }

  private BuildingState(boolean isChanged, GroupedList<SkyKey> lastBuildDirectDeps,
      SkyValue lastBuildValue) {
    this.lastBuildDirectDeps = lastBuildDirectDeps;
    this.lastBuildValue = Preconditions.checkNotNull(lastBuildValue);
    Preconditions.checkState(isChanged || !this.lastBuildDirectDeps.isEmpty(),
        "is being marked dirty, not changed, but has no children that could have dirtied it", this);
    dirtyState = isChanged ? DirtyState.REBUILDING : DirtyState.CHECK_DEPENDENCIES;
    if (dirtyState == DirtyState.CHECK_DEPENDENCIES) {
      // We need to iterate through the deps to see if they have changed. Initialize the iterator.
      dirtyDirectDepIterator = lastBuildDirectDeps.iterator();
    }
  }

  static BuildingState newDirtyState(boolean isChanged,
      GroupedList<SkyKey> lastBuildDirectDeps, SkyValue lastBuildValue) {
    return new BuildingState(isChanged, lastBuildDirectDeps, lastBuildValue);
  }

  void markChanged() {
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(!isChanged(), this);
    Preconditions.checkState(!evaluating, this);
    dirtyState = DirtyState.REBUILDING;
  }

  void forceChanged() {
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(!isChanged(), this);
    Preconditions.checkState(evaluating, this);
    Preconditions.checkState(isReady(), this);
    dirtyState = DirtyState.REBUILDING;
  }

  /**
   * Returns whether all known children of this value have signaled that they are done.
   */
  boolean isReady() {
    int directDepsSize = directDeps.size();
    Preconditions.checkState(signaledDeps <= directDepsSize, "%s %s", directDepsSize, this);
    return signaledDeps == directDepsSize;
  }

  /**
   * Returns true if the entry is marked dirty, meaning that at least one of its transitive
   * dependencies is marked changed.
   *
   * @see ValueEntry#isDirty()
   */
  boolean isDirty() {
    return dirtyState != null;
  }

  /**
   * Returns true if the entry is known to require re-evaluation.
   *
   * @see ValueEntry#isChanged()
   */
  boolean isChanged() {
    return dirtyState == DirtyState.REBUILDING;
  }

  private boolean rebuilding() {
    return dirtyState == DirtyState.REBUILDING;
  }

  /**
   * Helper method to assert that value has finished building, as far as we can tell. We would
   * actually like to check that the value has evaluated, but that is not available in
   * this context.
   */
  private void checkNotProcessing() {
    Preconditions.checkState(evaluating, "not started building %s", this);
    Preconditions.checkState(!isDirty() || dirtyState == DirtyState.VERIFIED_CLEAN
        || rebuilding(), "not done building %s", this);
    Preconditions.checkState(isReady(), "not done building %s", this);
  }

  /**
   * Puts the value in the "evaluating" state if it is not already in it. Returns whether or not the
   * value was already evaluating. Should only be called by
   * {@link ValueEntry#addReverseDepAndCheckIfDone}.
   */
  boolean startEvaluating() {
    boolean result = !evaluating;
    evaluating = true;
    return result;
  }

  /**
   * Increments the number of children known to be finished. Returns true if the number of children
   * finished is equal to the number of known children.
   *
   * <p>If the value is dirty and checking its deps for changes, this also updates {@link
   * #dirtyState} as needed -- {@link DirtyState#REBUILDING} if the child has changed,
   * and {@link DirtyState#VERIFIED_CLEAN} if the child has not changed and this was the last
   * child to be checked (as determined by {@link #dirtyDirectDepIterator} == null, isReady(), and
   * a flag set in {@link #getNextDirtyDirectDeps}).
   *
   * @see ValueEntry#signalDep(long)
   */
  boolean signalDep(boolean childChanged) {
    signaledDeps++;
    if (isDirty() && !rebuilding()) {
      // Synchronization isn't needed here because the only caller is ValueEntry, which does it
      // through the synchronized method signalDep(long).
      if (childChanged) {
        dirtyState = DirtyState.REBUILDING;
      } else if (dirtyState == DirtyState.CHECK_DEPENDENCIES && isReady()
          && dirtyDirectDepIterator == null) {
        // No other dep already marked this as REBUILDING, no deps outstanding, and this was
        // the last block of deps to be checked.
        dirtyState = DirtyState.VERIFIED_CLEAN;
      }
    }
    return isReady();
  }

  /**
   * Returns true if {@code newValue}.equals the value from the last time this value was built, and
   * the deps requested during this evaluation are exactly those requested the last time this value
   * was built, in the same order. Should only be used by {@link ValueEntry#setValue}.
   */
  boolean unchangedFromLastBuild(SkyValue newValue) {
    checkNotProcessing();
    return lastBuildValue.equals(newValue) && lastBuildDirectDeps.equals(directDeps);
  }

  boolean noDepsLastBuild() {
    return lastBuildDirectDeps.isEmpty();
  }

  SkyValue getLastBuildValue() {
    return Preconditions.checkNotNull(lastBuildValue, this);
  }

  /**
   * Gets the current state of checking this dirty entry to see if it must be re-evaluated. Must be
   * called each time evaluation of a dirty entry starts to find the proper action to perform next,
   * as enumerated by {@link DirtyState}.
   *
   * @see ValueEntry#getDirtyState()
   */
  DirtyState getDirtyState() {
    // Entry may not be ready if being built just for its errors.
    Preconditions.checkState(isDirty(), "must be dirty to get dirty state %s", this);
    Preconditions.checkState(evaluating, "must be evaluating to get dirty state %s", this);
    return dirtyState;
  }

  /**
   * Gets the next children to be re-evaluated to see if this dirty value needs to be re-evaluated.
   *
   * <p>If this is the last group of children to be checked, then sets {@link
   * #dirtyDirectDepIterator} to null so that the final call to {@link #signalDep(boolean)} will
   * know to mark this entry as {@link DirtyState#VERIFIED_CLEAN} if no deps have changed.
   *
   * See {@link ValueEntry#getNextDirtyDirectDeps}.
   */
  Collection<SkyKey> getNextDirtyDirectDeps() {
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(dirtyState == DirtyState.CHECK_DEPENDENCIES, this);
    Preconditions.checkState(evaluating, this);
    List<SkyKey> nextDeps = ImmutableList.copyOf(dirtyDirectDepIterator.next());
    if (!dirtyDirectDepIterator.hasNext()) {
      // Done checking deps. If this last group is clean, the state will become VERIFIED_CLEAN.
      dirtyDirectDepIterator = null;
    }
    return nextDeps;
  }

  void addDirectDeps(GroupedListHelper<SkyKey> depsThisRun) {
    directDeps.append(depsThisRun);
  }

  /**
   * Returns the direct deps found so far on this build. Should only be called before the value has
   * finished building.
   *
   * @see ValueEntry#getTemporaryDirectDeps()
   */
  Set<SkyKey> getDirectDepsForBuild() {
    return directDeps.toSet();
  }

  /**
   * Returns the direct deps (in groups) found on this build. Should only be called when the value
   * is done.
   *
   * @see ValueEntry#setStateFinishedAndReturnReverseDeps
   */
  GroupedList<SkyKey> getFinishedDirectDeps() {
    return directDeps;
  }

  /**
   * Returns reverse deps to signal that have been registered this build.
   *
   * @see ValueEntry#getReverseDeps()
   */
  ImmutableSet<SkyKey> getReverseDepsToSignal() {
    return REVERSE_DEPS_UTIL.getReverseDeps(this);
  }

  /**
   * Adds a reverse dependency that should be notified when this entry is done.
   *
   * @see ValueEntry#addReverseDepAndCheckIfDone(SkyKey)
   */
  void addReverseDepToSignal(SkyKey newReverseDep) {
    REVERSE_DEPS_UTIL.consolidateReverseDepsRemovals(this);
    REVERSE_DEPS_UTIL.addReverseDeps(this, Collections.singleton(newReverseDep));
  }

  /**
   * @see ValueEntry#removeReverseDep(SkyKey)
   */
  void removeReverseDepToSignal(SkyKey reverseDep) {
    REVERSE_DEPS_UTIL.removeReverseDep(this, reverseDep);
  }

  /**
   * Removes a set of deps from the set of known direct deps. This is complicated by the need
   * to maintain the group data. If we remove a dep that ended a group, then its predecessor's
   * group data must be changed to indicate that it now ends the group.
   *
   * @see ValueEntry#removeUnfinishedDeps
   */
  void removeDirectDeps(Set<SkyKey> unfinishedDeps) {
    directDeps.remove(unfinishedDeps);
  }

  @Override
  @SuppressWarnings("deprecation")
  public String toString() {
    return Objects.toStringHelper(this)  // MoreObjects is not in Guava
        .add("evaluating", evaluating)
        .add("dirtyState", dirtyState)
        .add("signaledDeps", signaledDeps)
        .add("directDeps", directDeps)
        .add("reverseDepsToSignal", REVERSE_DEPS_UTIL.toString(this))
        .add("lastBuildDirectDeps", lastBuildDirectDeps)
        .add("lastBuildValue", lastBuildValue)
        .add("dirtyDirectDepIterator", dirtyDirectDepIterator).toString();
  }
}
