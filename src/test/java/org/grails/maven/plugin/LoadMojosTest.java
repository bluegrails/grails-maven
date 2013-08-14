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

import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.codehaus.plexus.configuration.PlexusConfiguration;

import java.io.File;

/**
 * @author <a href="mailto:aheritier@gmail.com">Arnaud HERITIER</a>
 * @version $Id$
 */
public class LoadMojosTest extends AbstractMojoTestCase {

    private void mojoTest(String pluginConfig, String mojoName, Class mojoClass) throws Exception {
//        File testPom = getTestFile("src/test/resources/org/grails/maven/plugin/" + pluginConfig);
//        Object mojo = lookupMojo(mojoName, testPom);
//        assertNotNull(mojo);
//        assertEquals(mojo.getClass(), mojoClass);
//        release(mojo);
    }

    public void testLoadGrailsCleanMojoLookup() throws Exception {
        mojoTest("grails-clean/plugin-config.xml", "clean", GrailsCleanMojo.class);
    }

    public void testLoadGrailsExecMojoLookup() throws Exception {
        mojoTest("grails-exec/plugin-config.xml", "exec", GrailsExecMojo.class);
    }

    public void testLoadGrailsPackageMojoLookup() throws Exception {
        mojoTest("grails-package/plugin-config.xml", "package", GrailsPackageMojo.class);
    }

    public void testLoadGrailsRunAppMojoLookup() throws Exception {
        mojoTest("grails-run-app/plugin-config.xml", "run-app", GrailsRunAppMojo.class);
    }

    public void testLoadGrailsRunAppHttpsMojoLookup() throws Exception {
        mojoTest("grails-run-app-https/plugin-config.xml", "run-app-https", GrailsRunAppHttpsMojo.class);
    }

    public void testLoadGrailsTestAppMojoLookup() throws Exception {
        mojoTest("grails-test-app/plugin-config.xml", "test-app", GrailsTestAppMojo.class);
    }

    public void testLoadGrailsWarMojoLookup() throws Exception {
        mojoTest("grails-war/plugin-config.xml", "war", GrailsWarMojo.class);
    }

    public void testLoadMavenCleanMojoLookup() throws Exception {
        mojoTest("maven-clean/plugin-config.xml", "maven-clean", MvnCleanMojo.class);
    }

    public void testLoadMavenTestAppMojoLookup() throws Exception {
        mojoTest("maven-test-app/plugin-config.xml", "maven-test", MvnTestMojo.class);
    }

    public void testLoadMavenInitializeMojoLookup() throws Exception {
        mojoTest("maven-initialize/plugin-config.xml", "init", MvnInitializeMojo.class);
    }

    public void testLoadMavenValidateMojoLookup() throws Exception {
        mojoTest("maven-validate/plugin-config.xml", "validate", MvnValidateMojo.class);
    }

    public void testLoadMavenWarMojoLookup() throws Exception {
        mojoTest("maven-war/plugin-config.xml", "maven-war", MvnWarMojo.class);
    }
}
