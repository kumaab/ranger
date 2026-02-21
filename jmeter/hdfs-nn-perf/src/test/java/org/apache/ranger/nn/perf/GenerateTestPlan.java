/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.nn.perf;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.protocol.java.sampler.JavaSampler;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.Summariser;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class GenerateTestPlan {
    public static final Logger               logger     = LoggerFactory.getLogger(GenerateTestPlan.class);

    public static       StandardJMeterEngine jmeter     = new StandardJMeterEngine();
    public static       NNPerfTester         fileReader = new NNPerfTester(true);
    public static       Properties           props;
    public static       File                 jmeterHome;

    private GenerateTestPlan() {
        //To Block Instantiation
    }

    public static ListedHashTree generateXML() {
        ListedHashTree root = new ListedHashTree();
        props = fileReader.readProperties();
        String jmeterHomeStr = props.getProperty(Constants.JMETER_INSTALL_DIR) + "/apache-jmeter-" + props.getProperty(Constants.JMETER_VERSION);
        jmeterHome = new File(jmeterHomeStr);
        if (jmeterHome.exists()) {
            File jmeterProperties = new File(jmeterHome.getPath() + "/bin/jmeter.properties");
            if (jmeterProperties.exists()) {
                //JMeter initialization (properties, log levels, locale, etc)
                JMeterUtils.setJMeterHome(jmeterHome.getPath());
                JMeterUtils.loadJMeterProperties(jmeterProperties.getPath());
                JMeterUtils.initLogging(); // you can comment this line out to see extra log messages of i.e. DEBUG level
                JMeterUtils.initLocale();

                // Loop Controller
                LoopController loopController = new LoopController();
                loopController.setLoops(props.getProperty(Constants.TEST_PLAN_LOOPS));
                loopController.setFirst(true);
                loopController.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
                loopController.setProperty(TestElement.GUI_CLASS, LoopControlPanel.class.getName());
                loopController.initialize();

                // Thread Group
                ThreadGroup threadGroup = new ThreadGroup();
                threadGroup.setName(props.getProperty(Constants.TEST_PLAN_THREAD_GROUP_NAME));
                threadGroup.setNumThreads(Integer.parseInt(props.getProperty(Constants.TEST_PLAN_THREADS)));
                threadGroup.setRampUp(Integer.parseInt(props.getProperty(Constants.TEST_PLAN_RAMP_UP)));
                threadGroup.setSamplerController(loopController);
                threadGroup.setProperty(TestElement.TEST_CLASS, ThreadGroup.class.getName());
                threadGroup.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());

                // Test Plan
                TestPlan testPlan = new TestPlan(props.getProperty(Constants.TEST_PLAN_NAME));
                testPlan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
                testPlan.setProperty(TestElement.GUI_CLASS, TestPlanGui.class.getName());
                testPlan.setUserDefinedVariables((Arguments) new ArgumentsPanel().createTestElement());
                testPlan.setFunctionalMode(false);
                testPlan.setSerialized(false);

                // Create Java Request sampler
                JavaSampler javaSampler = new JavaSampler();
                javaSampler.setClassname(props.getProperty(Constants.TEST_PLAN_JAVA_CLASS));
                javaSampler.setName("Java Sampler");
                javaSampler.setProperty(TestElement.TEST_CLASS, JavaSampler.class.getName());
                javaSampler.setProperty(TestElement.GUI_CLASS, JavaSampler.class.getName());

                ListedHashTree testPlanSubtree    = root.add(testPlan);
                ListedHashTree threadGroupSubtree = testPlanSubtree.add(threadGroup);
                threadGroupSubtree.add(javaSampler);

                Path out = Paths.get(jmeterHome.getParentFile() + "/" + props.getProperty(Constants.TEST_PLAN_OUTPUT));

                try { // save generated test plan to JMeter's .jmx file format
                    SaveService.saveTree(root, Files.newOutputStream(out));
                    logger.info("JMeter .jmx script is available at {}", out);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return root;
    }

    public static void main(String[] args) {
        ListedHashTree root             = generateXML();
        File           jmeterHomeParent = jmeterHome.getParentFile();

        if (args.length > 0 && StringUtils.equals(args[0], "run")) {
            Summariser summer         = null;
            String     summariserName = JMeterUtils.getPropDefault("summariser.name", "summary");
            if (summariserName.length() > 0) {
                summer = new Summariser(summariserName);
            }

            // Store execution results into a .jtl file
            String          logFile         = jmeterHomeParent + "/" + props.getProperty(Constants.TEST_PLAN_RESULT);
            ResultCollector resultCollector = new ResultCollector(summer);
            resultCollector.setFilename(logFile);

            // Run Test Plan
            jmeter.configure(root);
            jmeter.run();
            logger.info("Test completed. See {} file for results", logFile);
        }
    }
}
