package org.grails.maven.plugin;


import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * Packages the binary Grails plugin.
 *
 * @author  Richard Vowles
 * @version $Id$
 * @description Packages the Grails plugin.
 * @goal package-binary-plugin
 * @phase package
 * @requiresProject true
 * @requiresDependencyResolution test
 * @since 0.4
 */
public class GrailsPackageBinaryPluginMojo extends AbstractGrailsMojo {

  /**
   * The artifact that this project produces.
   *
   * @parameter expression="${project.artifact}"
   * @required
   * @readonly
   */
  private Artifact artifact;

  /**
   * @component
   */

  private MavenProjectHelper projectHelper;

  /**
   * The artifact handler.
   *
   * @parameter expression="${component.org.apache.maven.artifact.handler.ArtifactHandler#grails-plugin2}"
   * @required
   * @readonly
   */
  protected ArtifactHandler artifactHandler;

  public void execute() throws MojoExecutionException, MojoFailureException {
    syncAppVersion();

    projectHelper.addResource(project, project.getBasedir().getAbsolutePath(), Arrays.asList("application.properties", getGrailsPluginFileName()), Collections.emptyList());

    // First package the plugin using the Grails script.
    runGrails("PackagePlugin", "--binary");
    renameJarToMavenExpectations();

    runGrails("PackagePlugin"); // no binary, do a source distribution
    File zipFile = GrailsPackagePluginMojo.renameToSourcePackage(project, getBasedir(), getLog(), null, null);

    projectHelper.attachArtifact( project, "zip", "plugin", zipFile );
  }

  private void renameJarToMavenExpectations() throws MojoExecutionException {
    // Now move the JAR from the project directory to the build
    // output directory.
    final String mavenFileName = project.getArtifactId() + "-" + project.getVersion() + ".jar";
    String jarFileName = PLUGIN_PREFIX + "plugin-";
    if (!mavenFileName.startsWith(PLUGIN_PREFIX))
      jarFileName += mavenFileName;
    else {
      jarFileName += mavenFileName.substring(PLUGIN_PREFIX.length());
    }
//
    File fileGeneratedByGrails = new File(project.getBuild().getDirectory(), jarFileName);
//
    File existingJarFile = new File(project.getBuild().getDirectory(), mavenFileName);

    if (!fileGeneratedByGrails.equals(existingJarFile)) {
      existingJarFile.delete();
      if (!fileGeneratedByGrails.renameTo(existingJarFile)) {
        throw new MojoExecutionException("Unable to copy the plugin binary JAR to the target directory (" + fileGeneratedByGrails.getAbsolutePath() + " to " + existingJarFile.getAbsolutePath() + ")");
      } else {
        getLog().info("Moved plugin binary JAR to '" + existingJarFile + "'.");
      }
    }

    artifact.setFile(existingJarFile);
    artifact.setArtifactHandler(artifactHandler);
  }
}