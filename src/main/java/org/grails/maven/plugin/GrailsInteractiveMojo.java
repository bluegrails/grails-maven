package org.grails.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Puts Grails in Interactive mode - but it doesn't work for some reason.
 *
 * @author Richard Vowles
 * @version $Id$
 * @description Puts Grails in Interactive mode
 * @since 1.20
 */
@Mojo(name = "interactive", requiresProject = true, requiresDependencyResolution = ResolutionScope.TEST)
public class GrailsInteractiveMojo extends AbstractGrailsMojo {
  public void execute() throws MojoExecutionException, MojoFailureException {
    nonInteractive = false;

    System.setProperty("grails.console.enable.terminal", "true");
    System.setProperty("-Dgrails.console.enable.interactive", "true");

    runGrails("Interactive");
  }
}
