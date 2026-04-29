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
package org.apache.ranger.examples.pdpclient;

import org.apache.commons.lang3.StringUtils;
import org.apache.ranger.authz.api.RangerAuthorizer;
import org.apache.ranger.authz.api.RangerAuthorizerFactory;
import org.apache.ranger.authz.model.RangerAccessContext;
import org.apache.ranger.authz.model.RangerAccessInfo;
import org.apache.ranger.authz.model.RangerAuthzRequest;
import org.apache.ranger.authz.model.RangerAuthzResult;
import org.apache.ranger.authz.model.RangerResourceInfo;
import org.apache.ranger.authz.model.RangerUserInfo;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

public final class RemoteAuthzClient {
    private static final String DEFAULT_PROPERTIES_RESOURCE = "/tmp/ranger-authz-remote-test.properties";

    private static final String PROP_TEST_REQUEST_ID            = "ranger.authz.remote.test.requestId";
    private static final String PROP_TEST_EXPECT_DECISION       = "ranger.authz.remote.test.expect.decision";
    private static final String PROP_TEST_USER_NAME             = "ranger.authz.remote.test.user.name";
    private static final String PROP_TEST_USER_GROUPS           = "ranger.authz.remote.test.user.groups";
    private static final String PROP_TEST_USER_ROLES            = "ranger.authz.remote.test.user.roles";
    private static final String PROP_TEST_ACCESS_ACTION         = "ranger.authz.remote.test.access.action";
    private static final String PROP_TEST_ACCESS_PERMISSIONS    = "ranger.authz.remote.test.access.permissions";
    private static final String PROP_TEST_RESOURCE_NAME         = "ranger.authz.remote.test.resource.name";
    private static final String PROP_TEST_RESOURCE_SUBRESOURCES = "ranger.authz.remote.test.resource.subResources";
    private static final String PROP_TEST_CONTEXT_SERVICE_TYPE  = "ranger.authz.remote.test.context.serviceType";
    private static final String PROP_TEST_CONTEXT_SERVICE_NAME  = "ranger.authz.remote.test.context.serviceName";
    private static final String PROP_TEST_CONTEXT_CLIENT_IP     = "ranger.authz.remote.test.context.clientIpAddress";

    private RemoteAuthzClient() {
    }

    public static void main(String[] args) throws Exception {
        String     propertiesLocation = args != null && args.length > 0 ? args[0] : "classpath:" + DEFAULT_PROPERTIES_RESOURCE;
        Properties properties         = loadProperties(propertiesLocation);

        System.out.println("Loaded properties from: " + propertiesLocation);

        RangerAuthzResult result = run(properties);

        System.out.println("Decision: " + result.getDecision());
        System.out.println("Result   : " + result);

        String expectedDecision = StringUtils.trimToNull(properties.getProperty(PROP_TEST_EXPECT_DECISION));

        if (expectedDecision != null && !expectedDecision.equalsIgnoreCase(String.valueOf(result.getDecision()))) {
            throw new IllegalStateException("Expected decision " + expectedDecision + " but got " + result.getDecision());
        }
    }

    static RangerAuthzResult run(Properties properties) throws Exception {
        RangerAuthorizer authorizer = RangerAuthorizerFactory.createAuthorizer(properties);

        try {
            authorizer.init();

            RangerAuthzRequest request = buildRequest(properties);

            System.out.println("Request  : " + request);

            return authorizer.authorize(request);
        } finally {
            authorizer.close();
        }
    }

    static Properties loadProperties(String location) throws Exception {
        Properties properties = new Properties();

        try (InputStream input = openInputStream(location)) {
            properties.load(input);
        }

        return properties;
    }

    private static InputStream openInputStream(String location) throws Exception {
        String effectiveLocation = StringUtils.defaultIfBlank(location, "classpath:" + DEFAULT_PROPERTIES_RESOURCE);

        if (effectiveLocation.startsWith("classpath:")) {
            String resourcePath = effectiveLocation.substring("classpath:".length());
            InputStream input = RemoteAuthzClient.class.getResourceAsStream(resourcePath);

            if (input == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resourcePath);
            }

            return input;
        }

        return new FileInputStream(effectiveLocation);
    }

    private static RangerAuthzRequest buildRequest(Properties properties) {
        RangerUserInfo      user     = new RangerUserInfo();
        RangerAccessInfo    access   = new RangerAccessInfo();
        RangerResourceInfo  resource = new RangerResourceInfo();
        RangerAccessContext context  = new RangerAccessContext();

        user.setName(properties.getProperty(PROP_TEST_USER_NAME, "hive"));
        user.setGroups(toSet(properties.getProperty(PROP_TEST_USER_GROUPS)));
        user.setRoles(toSet(properties.getProperty(PROP_TEST_USER_ROLES)));

        resource.setName(properties.getProperty(PROP_TEST_RESOURCE_NAME, "table:default/sales"));
        resource.setSubResources(toSet(properties.getProperty(PROP_TEST_RESOURCE_SUBRESOURCES)));

        access.setAction(properties.getProperty(PROP_TEST_ACCESS_ACTION, "QUERY"));
        access.setPermissions(toSet(properties.getProperty(PROP_TEST_ACCESS_PERMISSIONS, "select")));
        access.setResource(resource);

        context.setServiceType(properties.getProperty(PROP_TEST_CONTEXT_SERVICE_TYPE, "hive"));
        context.setServiceName(properties.getProperty(PROP_TEST_CONTEXT_SERVICE_NAME, "dev_hive"));
        context.setClientIpAddress(StringUtils.trimToNull(properties.getProperty(PROP_TEST_CONTEXT_CLIENT_IP)));
        context.setAdditionalInfo(new LinkedHashMap<String, Object>());

        return new RangerAuthzRequest(properties.getProperty(PROP_TEST_REQUEST_ID, "remote-smoke-test"), user, access, context);
    }

    private static Set<String> toSet(String value) {
        String trimmed = StringUtils.trimToNull(value);

        if (trimmed == null) {
            return null;
        }

        Set<String> ret = new LinkedHashSet<>();

        Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .forEach(ret::add);

        return ret.isEmpty() ? null : ret;
    }
}
