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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.rules.test.BaselineCoverageAction;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProviderImpl;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.AlwaysBuiltArtifactsProvider;
import com.google.devtools.build.lib.view.ConfiguredTarget;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.Runfiles;
import com.google.devtools.build.lib.view.RunfilesProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * A ConfiguredTarget for <code>cc_library</code> rules.
 */
public abstract class CcLibrary implements RuleConfiguredTargetFactory {

  private final CppSemantics semantics;

  protected CcLibrary(CppSemantics semantics) {
    this.semantics = semantics;
  }

  // These file extensions don't generate object files.
  private static final FileTypeSet NO_OBJECT_GENERATING_FILETYPES = FileTypeSet.of(
      CppFileTypes.CPP_HEADER, CppFileTypes.ARCHIVE, CppFileTypes.PIC_ARCHIVE,
      CppFileTypes.ALWAYS_LINK_LIBRARY, CppFileTypes.ALWAYS_LINK_PIC_LIBRARY,
      CppFileTypes.SHARED_LIBRARY);

  private static final Predicate<LibraryToLink> PIC_STATIC_FILTER = new Predicate<LibraryToLink>() {
    @Override
    public boolean apply(LibraryToLink input) {
      String name = input.getArtifact().getExecPath().getBaseName();
      return !name.endsWith(".nopic.a") && !name.endsWith(".nopic.lo");
    }
  };

  private static Runfiles collectRunfiles(RuleContext context,
      CcLinkingOutputs ccLinkingOutputs,
      boolean neverLink, boolean addDynamicRuntimeInputArtifactsToRunfiles,
      boolean linkingStatically) {
    Runfiles.Builder builder = new Runfiles.Builder();

    // neverlink= true creates a library that will never be linked into any binary that depends on
    // it, but instead be loaded as an extension. So we need the dynamic library for this in the
    // runfiles.
    builder.addArtifacts(ccLinkingOutputs.getLibrariesForRunfiles(linkingStatically && !neverLink));
    builder.add(context, CppRunfilesProvider.runfilesFunction(linkingStatically));
    if (context.getRule().isAttrDefined("implements", Type.LABEL_LIST)) {
      builder.addTargets(context.getPrerequisites("implements", Mode.TARGET),
          RunfilesProvider.DEFAULT_RUNFILES);
      builder.addTargets(context.getPrerequisites("implements", Mode.TARGET),
          CppRunfilesProvider.runfilesFunction(linkingStatically));
    }
    if (context.getRule().isAttrDefined("implementation", Type.LABEL_LIST)) {
      builder.addTargets(context.getPrerequisites("implementation", Mode.TARGET),
          RunfilesProvider.DEFAULT_RUNFILES);
      builder.addTargets(context.getPrerequisites("implementation", Mode.TARGET),
          CppRunfilesProvider.runfilesFunction(linkingStatically));
    }

    builder.addDataDeps(context);

    if (addDynamicRuntimeInputArtifactsToRunfiles) {
      builder.addTransitiveArtifacts(CppHelper.getToolchain(context).getDynamicRuntimeLinkInputs());
    }
    return builder.build();
  }

  @Override
  public ConfiguredTarget create(RuleContext context) {
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(context);
    LinkTargetType linkType = getStaticLinkType(context);
    boolean linkStatic = context.attributes().get("linkstatic", Type.BOOLEAN);
    init(semantics, context, builder, linkType,
        /*neverLink =*/ false,
        linkStatic,
        /*collectLinkstamp =*/ true,
        /*addDynamicRuntimeInputArtifactsToRunfiles =*/ false);
    return builder.build();
  }

  public static void init(CppSemantics semantics, RuleContext ruleContext,
      RuleConfiguredTargetBuilder targetBuilder, LinkTargetType linkType,
      boolean neverLink,
      boolean linkStatic,
      boolean collectLinkstamp,
      boolean addDynamicRuntimeInputArtifactsToRunfiles) {
    final CcCommon common = new CcCommon(ruleContext, semantics,
        /*initExtraPrerequisites =*/ true);

    CcLibraryHelper helper = new CcLibraryHelper(ruleContext, semantics)
        .setLinkType(linkType)
        .enableCcNativeLibrariesProvider()
        .enableInterfaceSharedObjects()
        .enableCompileProviders()
        .setNeverLink(neverLink)
        .setHeadersCheckingMode(common.determineHeadersCheckingMode())
        .addCopts(common.getCopts())
        .setNoCopts(common.getNoCopts())
        .addLinkopts(common.getLinkopts())
        .addDefines(common.getDefines())
        .addCompilationPrerequisites(common.getSharedLibrariesFromSrcs())
        .addCompilationPrerequisites(common.getStaticLibrariesFromSrcs())
        .addSources(common.getCAndCppSources())
        .addPublicHeaders(common.getHeaders())
        .addObjectFiles(common.getObjectFilesFromSrcs(false))
        .addPicObjectFiles(common.getObjectFilesFromSrcs(true))
        .addPicIndependentObjectFiles(common.getLinkerScripts())
        .addDeps(ruleContext.getPrerequisites("deps", Mode.TARGET))
        .addPlugins(common.getPlugins())
        .setEnableLayeringCheck(ruleContext.getFeatures().contains("layering_check"))
        .addSystemIncludeDirs(common.getSystemIncludeDirs())
        .addIncludeDirs(common.getIncludeDirs())
        .addLooseIncludeDirs(common.getLooseIncludeDirs())
        .setEmitHeaderTargetModuleMaps(
            ruleContext.getRule().getRuleClass().equals("cc_public_library"));
    
    if (collectLinkstamp) {
      helper.addLinkstamps(ruleContext.getPrerequisites("linkstamp", Mode.TARGET));
    }

    if (ruleContext.getRule().isAttrDefined("implements", Type.LABEL_LIST)) {
      helper.addDeps(ruleContext.getPrerequisites("implements", Mode.TARGET));
    }

    if (ruleContext.getRule().isAttrDefined("implementation", Type.LABEL_LIST)) {
      helper.addDeps(ruleContext.getPrerequisites("implementation", Mode.TARGET));
    }

    PathFragment soImplFilename = null;
    if (ruleContext.getRule().isAttrDefined("outs", Type.STRING_LIST)) {
      List<String> outs = ruleContext.attributes().get("outs", Type.STRING_LIST);
      if (outs.size() > 1) {
        ruleContext.attributeError("outs", "must be a singleton list");
      } else if (outs.size() == 1) {
        soImplFilename = CppHelper.getLinkedFilename(ruleContext, LinkTargetType.DYNAMIC_LIBRARY);
        soImplFilename = soImplFilename.replaceName(outs.iterator().next());
        if (!soImplFilename.getPathString().endsWith(".so")) { // Sanity check.
          ruleContext.attributeError("outs", "file name must end in '.so'");
        }
      }
    }

    if (ruleContext.getRule().isAttrDefined("srcs", Type.LABEL_LIST)) {
      helper.addPrivateHeaders(FileType.filter(
          ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET),
          CppFileTypes.CPP_HEADER));
      ruleContext.checkSrcsSamePackage(true);
    }

    if (common.getLinkopts().contains("-static")) {
      ruleContext.attributeWarning("linkopts", "Using '-static' here won't work. "
                                   + "Did you mean to use 'linkstatic=1' instead?");
    }

    boolean createDynamicLibrary =
        !linkStatic && !appearsToHaveNoObjectFiles(ruleContext.attributes());
    helper.setCreateDynamicLibrary(createDynamicLibrary);
    helper.setDynamicLibraryPath(soImplFilename);

    /*
     * Add the libraries from srcs, if any. For static/mostly static
     * linking we setup the dynamic libraries if there are no static libraries
     * to choose from. Path to the libraries will be mangled to avoid using
     * absolute path names on the -rpath, but library filenames will be
     * preserved (since some libraries might have SONAME tag) - symlink will
     * be created to the parent directory instead.
     *
     * For compatibility with existing BUILD files, any ".a" or ".lo" files listed in
     * srcs are assumed to be position-independent code, or at least suitable for
     * inclusion in shared libraries, unless they end with ".nopic.a" or ".nopic.lo".
     *
     * Note that some target platforms do not require shared library code to be PIC.
     */
    Iterable<LibraryToLink> staticLibrariesFromSrcs =
        LinkerInputs.opaqueLibrariesToLink(common.getStaticLibrariesFromSrcs());
    helper.addStaticLibraries(staticLibrariesFromSrcs);
    helper.addPicStaticLibraries(Iterables.filter(staticLibrariesFromSrcs, PIC_STATIC_FILTER));
    helper.addPicStaticLibraries(common.getPicStaticLibrariesFromSrcs());
    helper.addDynamicLibraries(Iterables.transform(common.getSharedLibrariesFromSrcs(),
        new Function<Artifact, LibraryToLink>() {
      @Override
      public LibraryToLink apply(Artifact library) {
        return common.getDynamicLibrarySymlink(library, true);
      }
    }));
    CcLibraryHelper.Info info = helper.build();

    /*
     * We always generate a static library, even if there aren't any source files.
     * This keeps things simpler by avoiding special cases when making use of the library.
     * For example, this is needed to ensure that building a library with "bazel build"
     * will also build all of the library's "deps".
     * However, we only generate a dynamic library if there are source files.
     */
    // For now, we don't add the precompiled libraries to the files to build.
    CcLinkingOutputs linkedLibraries = info.getCcLinkingOutputsExcludingPrecompiledLibraries();

    NestedSet<Artifact> artifactsToForce =
        collectArtifactsToForce(ruleContext, common, info.getCcCompilationOutputs());

    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    filesBuilder.addAll(LinkerInputs.toLibraryArtifacts(linkedLibraries.getStaticLibraries()));
    filesBuilder.addAll(LinkerInputs.toLibraryArtifacts(linkedLibraries.getPicStaticLibraries()));
    filesBuilder.addAll(LinkerInputs.toNonSolibArtifacts(linkedLibraries.getDynamicLibraries()));
    filesBuilder.addAll(
        LinkerInputs.toNonSolibArtifacts(linkedLibraries.getExecutionDynamicLibraries()));

    CcLinkingOutputs linkingOutputs = info.getCcLinkingOutputs();
    warnAboutEmptyLibraries(
        ruleContext, info.getCcCompilationOutputs(), linkType, linkStatic);
    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    Runfiles staticRunfiles = collectRunfiles(ruleContext,
        linkingOutputs, neverLink, addDynamicRuntimeInputArtifactsToRunfiles, true);
    Runfiles sharedRunfiles = collectRunfiles(ruleContext,
        linkingOutputs, neverLink, addDynamicRuntimeInputArtifactsToRunfiles, false);

    List<Artifact> instrumentedObjectFiles = new ArrayList<>();
    instrumentedObjectFiles.addAll(info.getCcCompilationOutputs().getObjectFiles(false));
    instrumentedObjectFiles.addAll(info.getCcCompilationOutputs().getObjectFiles(true));
    targetBuilder
        .setFilesToBuild(filesToBuild)
        .addProviders(info.getProviders())
        .add(InstrumentedFilesProvider.class, new InstrumentedFilesProviderImpl(
            common.getInstrumentedFiles(instrumentedObjectFiles),
            common.getInstrumentationMetadataFiles(instrumentedObjectFiles)))
        .add(RunfilesProvider.class, RunfilesProvider.withData(staticRunfiles, sharedRunfiles))
        // Remove this?
        .add(CppRunfilesProvider.class, new CppRunfilesProvider(staticRunfiles, sharedRunfiles))
        .setBaselineCoverageArtifacts(BaselineCoverageAction.getBaselineCoverageArtifacts(
            ruleContext, common.getInstrumentedFiles(filesToBuild)))
        .add(ImplementedCcPublicLibrariesProvider.class,
            new ImplementedCcPublicLibrariesProvider(getImplementedCcPublicLibraries(ruleContext)))
        .add(AlwaysBuiltArtifactsProvider.class,
            new AlwaysBuiltArtifactsProvider(artifactsToForce));
  }

  private static NestedSet<Artifact> collectArtifactsToForce(RuleContext ruleContext,
      CcCommon common, CcCompilationOutputs ccCompilationOutputs) {
    // Ensure that we build all the dependencies, otherwise users may get confused.
    NestedSetBuilder<Artifact> artifactsToForceBuilder = NestedSetBuilder.stableOrder();
    artifactsToForceBuilder.addTransitive(
        NestedSetBuilder.wrap(Order.STABLE_ORDER, common.getFilesToCompile(ccCompilationOutputs)));
    for (AlwaysBuiltArtifactsProvider dep :
        ruleContext.getPrerequisites("deps", Mode.TARGET, AlwaysBuiltArtifactsProvider.class)) {
      artifactsToForceBuilder.addTransitive(dep.getArtifactsToAlwaysBuild());
    }
    return artifactsToForceBuilder.build();
  }

  /**
   * Returns the type of the generated static library.
   */
  private static LinkTargetType getStaticLinkType(RuleContext context) {
    return context.attributes().get("alwayslink", Type.BOOLEAN)
        ? LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY
        : LinkTargetType.STATIC_LIBRARY;
  }

  private static void warnAboutEmptyLibraries(RuleContext ruleContext,
      CcCompilationOutputs ccCompilationOutputs, LinkTargetType linkType,
      boolean linkstaticAttribute) {
    if (ruleContext.getFragment(CppConfiguration.class).isLipoContextCollector()) {
      // Do not signal warnings in the lipo context collector configuration. These will be duly
      // signaled in the target configuration, and there can be spurious warnings since targets in
      // the LIPO context collector configuration do not compile anything.
      return;
    }
    if (ccCompilationOutputs.getObjectFiles(false).isEmpty()
        && ccCompilationOutputs.getObjectFiles(true).isEmpty()) {
      if (linkType == LinkTargetType.ALWAYS_LINK_STATIC_LIBRARY
          || linkType == LinkTargetType.ALWAYS_LINK_PIC_STATIC_LIBRARY) {
        ruleContext.attributeWarning("alwayslink",
            "'alwayslink' has no effect if there are no 'srcs'");
      }
      if (!linkstaticAttribute && !appearsToHaveNoObjectFiles(ruleContext.attributes())) {
        ruleContext.attributeWarning("linkstatic",
            "setting 'linkstatic=1' is recommended if there are no object files");
      }
    } else {
      if (!linkstaticAttribute && appearsToHaveNoObjectFiles(ruleContext.attributes())) {
        Artifact element = ccCompilationOutputs.getObjectFiles(false).isEmpty()
            ? ccCompilationOutputs.getObjectFiles(true).iterator().next()
            : ccCompilationOutputs.getObjectFiles(false).iterator().next();
        ruleContext.attributeWarning("srcs",
             "this library appears at first glance to have no object files, "
             + "but on closer inspection it does have something to link, e.g. "
             + element.prettyPrint() + ". "
             + "(You may have used some very confusing rule names in srcs? "
             + "Or the library consists entirely of a linker script?) "
             + "Bazel assumed linkstatic=1, but this may be inappropriate. "
             + "You may need to add an explicit '.cc' file to 'srcs'. "
             + "Alternatively, add 'linkstatic=1' to suppress this warning");
      }
    }
  }

  private static ImmutableList<Label> getImplementedCcPublicLibraries(RuleContext context) {
    if (context.getRule().getRuleClassObject().hasAttr("implements", Type.LABEL_LIST)) {
      return ImmutableList.copyOf(context.attributes().get("implements", Type.LABEL_LIST));
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * Returns true if the rule (which must be a cc_library rule)
   * appears to have no object files.  This only looks at the rule
   * itself, not at any other rules (from this package or other
   * packages) that it might reference.
   *
   * <p>
   * In some cases, this may return "false" even
   * though the rule actually has no object files.
   * For example, it will return false for a rule such as
   * <code>cc_library(name = 'foo', srcs = [':bar'])</code>
   * because we can't tell what ':bar' is; it might
   * be a genrule that generates a source file, or it might
   * be a genrule that generates a header file.
   *
   * <p>
   * In other cases, this may return "true" even
   * though the rule actually does have object files.
   * For example, it will return true for a rule such as
   * <code>cc_library(name = 'foo', srcs = ['bar.h'])</code>
   * but as in the other example above, we can't tell whether
   * 'bar.h' is a file name or a rule name, and 'bar.h' could
   * in fact be the name of a genrule that generates a source file.
   */
  public static boolean appearsToHaveNoObjectFiles(AttributeMap rule) {
    // Temporary hack while configurable attributes is under development. This has no effect
    // for any rule that doesn't use configurable attributes.
    // TODO(bazel-team): remove this hack for a more principled solution.
    try {
      rule.get("srcs", Type.LABEL_LIST);
    } catch (ClassCastException e) {
      // "srcs" is actually a configurable selector. Assume object files are possible somewhere.
      return false;
    }

    List<Label> srcs = rule.get("srcs", Type.LABEL_LIST);
    if (srcs != null) {
      for (Label srcfile : srcs) {
        /*
         * We cheat a little bit here by looking at the file extension
         * of the Label treated as file name.  In general that might
         * not necessarily work, because of the possibility that the
         * user might give a rule a funky name ending in one of these
         * extensions, e.g.
         *    genrule(name = 'foo.h', outs = ['foo.cc'], ...) // Funky rule name!
         *    cc_library(name = 'bar', srcs = ['foo.h']) // This DOES have object files.
         */
        if (!NO_OBJECT_GENERATING_FILETYPES.matches(srcfile.getName())) {
          return false;
        }
      }
    }
    return true;
  }
}
