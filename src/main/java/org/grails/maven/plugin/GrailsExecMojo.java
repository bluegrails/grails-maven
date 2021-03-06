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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import grails.util.GrailsNameUtils;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Executes an arbitrary Grails command.
 *
 * @author <a href="mailto:aheritier@gmail.com">Arnaud HERITIER</a>
 * @version $Id$
 * @description Executes an arbitrary Grails command.
 * @since 0.4
 */
@Mojo(name = "exec", requiresProject = false, requiresDependencyResolution = ResolutionScope.TEST)
public class GrailsExecMojo extends AbstractGrailsMojo {

  /**
   * Set this to name of the command you want to execute.
   *
   */
  @Parameter(property = "command", required = true)
  private String command;

  /**
   * Set this to the arguments you want to pass to the command.
   *
   */
  @Parameter(property = "args")
  private String args;

  public void execute() throws MojoExecutionException, MojoFailureException {
    String nameFromScript = GrailsNameUtils.getNameFromScript(command);

    if (nameFromScript.equalsIgnoreCase("testapp"))
      env = "test";
    if (nameFromScript.equalsIgnoreCase("war"))
      env = "prod";

    runGrails(nameFromScript, args);
  }
}
