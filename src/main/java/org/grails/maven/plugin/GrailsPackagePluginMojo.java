/*
 * Copyright 2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.maven.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * Packages the Grails plugin.
 *
 * @author <a href="mailto:aheritier@gmail.com">Arnaud HERITIER</a>, Richard Vowles
 * @version $Id$
 * @description Packages the Grails plugin.
 * @since 0.4
 */
@Mojo(name = "package-plugin", requiresProject = true, requiresDependencyResolution = ResolutionScope.TEST, defaultPhase = LifecyclePhase.PACKAGE)
public class GrailsPackagePluginMojo extends AbstractGrailsMojo {

  /**
   * The artifact that this project produces.
   *
   */
  @Parameter(property = "project.artifact", required = true, readonly = true)
  private Artifact artifact;

  /**
   * The artifact handler.
   *
   */
  @Parameter(property = "component.org.apache.maven.artifact.handler.ArtifactHandler#grails-plugin")
  protected ArtifactHandler artifactHandler;

  /**
   */
  @Component
  private MavenProjectHelper projectHelper;

  public void execute() throws MojoExecutionException, MojoFailureException {
    syncAppVersion();

    projectHelper.addResource(project, project.getBasedir().getAbsolutePath(), Arrays.asList("application.properties", getGrailsPluginFileName()), Collections.emptyList());

    // First package the plugin using the Grails script.
    runGrails("PackagePlugin");
//    runGrails("PackagePlugin", "--binary");

    // Now move the ZIP from the project directory to the build
    // output directory.
    renameToSourcePackage(project, getBasedir(), getLog(), artifact, artifactHandler);
  }

  public static File renameToSourcePackage(MavenProject project, File baseDir, org.apache.maven.plugin.logging.Log log,
                                           Artifact artifact, ArtifactHandler artifactHandler)
    throws MojoExecutionException {
    String zipFileName = project.getArtifactId() + "-" + project.getVersion() + ".zip";
    if (!zipFileName.startsWith(PLUGIN_PREFIX)) zipFileName = PLUGIN_PREFIX + zipFileName;

    File zipGeneratedByGrails = new File(baseDir, zipFileName);

    File mavenZipFile = new File(project.getBuild().getDirectory(), zipFileName);
    mavenZipFile.delete();
    if (!zipGeneratedByGrails.renameTo(mavenZipFile)) {
      throw new MojoExecutionException("Unable to rename the plugin ZIP to the target directory (" + zipGeneratedByGrails.getAbsolutePath() + " to " + mavenZipFile.getAbsolutePath() + ") - perhaps the application.properties version is out of sync?");
    } else {
      log.info("Moved plugin ZIP to '" + mavenZipFile + "'.");
    }

    if (artifact != null) {
      // Attach the zip file to the "grails-plugin" artifact, otherwise
      // the "install" and "deploy" phases won't work.
      artifact.setFile(mavenZipFile);
      artifact.setArtifactHandler(artifactHandler);
    }

    return mavenZipFile;
  }
}
