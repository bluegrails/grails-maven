package org.grails.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Puts Grails in Interactive mode - but it doesn't work for some reason.
 *
 * @author Richard Vowles
 * @version $Id$
 * @description Puts Grails in Interactive mode
 * @goal interactive
 * @requiresProject true
 * @requiresDependencyResolution test
 * @since 1.20
 */
public class GrailsInteractiveMojo extends AbstractGrailsMojo {
  public void execute() throws MojoExecutionException, MojoFailureException {
    nonInteractive = false;
    runGrails("Interactive");
  }
}
