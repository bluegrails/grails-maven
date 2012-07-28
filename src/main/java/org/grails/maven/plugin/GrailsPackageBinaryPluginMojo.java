package org.grails.maven.plugin;


import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.io.File;

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
   * The artifact handler.
   *
   * @parameter expression="${component.org.apache.maven.artifact.handler.ArtifactHandler#grails-plugin}"
   * @required
   * @readonly
   */
  protected ArtifactHandler artifactHandler;

  public void execute() throws MojoExecutionException, MojoFailureException {
    syncAppVersion();

    // First package the plugin using the Grails script.
    runGrails("PackagePlugin", "--binary");

    // Now move the JAR from the project directory to the build
    // output directory.
    final String mavenFileName = project.getArtifactId() + "-" + project.getVersion() + ".jar";
    String jarFileName = PLUGIN_PREFIX + "plugin-" + mavenFileName;
//
    File fileGeneratedByGrails = new File(project.getBuild().getDirectory(), jarFileName);
//
    File existingJarFile = new File(project.getBuild().getDirectory(), mavenFileName);
    existingJarFile.delete();
    if (!fileGeneratedByGrails.renameTo(existingJarFile)) {
      throw new MojoExecutionException("Unable to copy the plugin ZIP to the target directory (" + fileGeneratedByGrails.getAbsolutePath() + " to " + existingJarFile.getAbsolutePath() + ")");
    } else {
      getLog().info("Moved plugin ZIP to '" + existingJarFile + "'.");
    }

//
//    // Attach the zip file to the "grails-plugin" artifact, otherwise
//    // the "install" and "deploy" phases won't work.
//    artifact.setFile(mavenZipFile);
//
//    artifact.setArtifactHandler(artifactHandler);
  }
}