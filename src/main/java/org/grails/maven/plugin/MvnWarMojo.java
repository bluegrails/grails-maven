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

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;

/**
 * Creates a WAR archive for the project and puts it in the usual Maven
 * place.
 *
 * @author <a href="mailto:aheritier@gmail.com">Arnaud HERITIER</a>
 * @version $Id$
 * @description Creates a WAR archive and register it in maven.
 * @since 0.1
 */
@Mojo(name = "maven-war", requiresProject = true, requiresDependencyResolution = ResolutionScope.TEST, defaultPhase = LifecyclePhase.PACKAGE)
public class MvnWarMojo extends AbstractGrailsMojo {
  protected File warFile;

  /**
   */
  @Component
  private MavenProjectHelper projectHelper;

  /**
   * Executes the MvnWarMojo on the current project.
   *
   * @throws MojoExecutionException if an error occured while building the webapp
   */
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (!"grails-plugin".equals(project.getPackaging()) && !"grails-plugin2".equalsIgnoreCase(project.getPackaging())) {
      Build build = project.getBuild();
      String warFileName = build.getFinalName() != null ? build.getFinalName() : project.getArtifactId() + "-" + project.getVersion();
      if (!warFileName.endsWith(".war")) {
        warFileName += ".war";
      }
      warFile = new File(build.getDirectory(), warFileName);

      projectHelper.addResource(project, project.getBasedir().getAbsolutePath(), Arrays.asList("application.properties"), Collections.emptyList());

      try {
        env = "prod";
        runGrails("War", warFile.toString());
      } finally {
        env = null;
      }
    }
  }
}
