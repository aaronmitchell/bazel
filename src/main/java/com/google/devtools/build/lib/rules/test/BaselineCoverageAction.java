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

package com.google.devtools.build.lib.rules.test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.Constants;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Util;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.Fingerprint;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates baseline (empty) coverage for the given non-test target.
 */
public class BaselineCoverageAction extends AbstractFileWriteAction
    implements NotifyOnActionCacheHit {

  private final Iterable<Artifact> instrumentedFiles;

  private BaselineCoverageAction(
      ActionOwner owner, Iterable<Artifact> instrumentedFiles, Artifact output) {
    super(owner, ImmutableList.<Artifact>of(), output, false);
    this.instrumentedFiles = instrumentedFiles;
  }

  @Override
  public String getMnemonic() {
    return "BaselineCoverage";
  }

  @Override
  public String computeKey() {
    return new Fingerprint()
        .addStrings(getInstrumentedFilePathStrings())
        .hexDigestAndReset();
  }

  private Iterable<String> getInstrumentedFilePathStrings() {
    List<String> result = new ArrayList<>();
    for (Artifact instrumentedFile : instrumentedFiles) {
      String pathString = instrumentedFile.getExecPathString();
      for (String suffix : Constants.BASELINE_COVERAGE_OFFLINE_INSTRUMENTATION_SUFFIXES) {
        if (pathString.endsWith(suffix)) {
          result.add(pathString);
          break;
        }
      }
    }

    return result;
  }

  @Override
  public DeterministicWriter newDeterministicWriter(EventHandler eventHandler,
      Executor executor) {
    return new DeterministicWriter() {
      @Override
      public void writeOutputFile(OutputStream out) throws IOException {
        PrintWriter writer = new PrintWriter(out);
        for (String execPath : getInstrumentedFilePathStrings()) {
          writer.write("SF:" + execPath + "\n");
          writer.write("end_of_record\n");
        }
        writer.flush();
      }
    };
  }

  @Override
  protected void afterWrite(Executor executor) {
    notifyAboutBaselineCoverage(executor.getEventBus());
  }

  @Override
  public void actionCacheHit(Executor executor) {
    notifyAboutBaselineCoverage(executor.getEventBus());
  }

  /**
   * Notify interested parties about new baseline coverage data.
   */
  private void notifyAboutBaselineCoverage(EventBus eventBus) {
    Artifact output = Iterables.getOnlyElement(getOutputs());
    String ownerString = Label.print(getOwner().getLabel());
    eventBus.post(new BaselineCoverageResult(output, ownerString));
  }

  /**
   * Returns collection of baseline coverage artifacts associated with the given target.
   * Will always return 0 or 1 elements.
   */
  public static NestedSet<Artifact> getBaselineCoverageArtifacts(RuleContext ruleContext,
      Iterable<Artifact> instrumentedFiles) {
    // Baseline coverage artifacts will still go into "testlogs" directory.
    Artifact coverageData = ruleContext.getAnalysisEnvironment().getDerivedArtifact(
        Util.getWorkspaceRelativePath(ruleContext.getTarget()).getChild("baseline_coverage.dat"),
        ruleContext.getConfiguration().getTestLogsDirectory());
    ruleContext.registerAction(new BaselineCoverageAction(
        ruleContext.getActionOwner(), instrumentedFiles, coverageData));

    return NestedSetBuilder.create(Order.STABLE_ORDER, coverageData);
  }

}
