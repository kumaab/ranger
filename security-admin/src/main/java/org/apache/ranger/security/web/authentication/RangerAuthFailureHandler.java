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

/**
 *
 */
package org.apache.ranger.security.web.authentication;

import org.apache.ranger.common.JSONUtil;
import org.apache.ranger.common.PropertiesUtil;
import org.apache.ranger.util.CLIUtil;
import org.apache.ranger.view.VXResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 *
 */
public class RangerAuthFailureHandler extends ExceptionMappingAuthenticationFailureHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RangerAuthFailureHandler.class);

    String ajaxLoginfailurePage;

    @Autowired
    JSONUtil jsonUtil;

    public RangerAuthFailureHandler() {
        super();

        if (ajaxLoginfailurePage == null) {
            ajaxLoginfailurePage = PropertiesUtil.getProperty("ranger.ajax.auth.failure.page", "/ajax_failure.jsp");
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.springframework.security.web.authentication.
     * ExceptionMappingAuthenticationFailureHandler
     * #onAuthenticationFailure(javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse,
     * org.springframework.security.core.AuthenticationException)
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        String ajaxRequestHeader = request.getHeader("X-Requested-With");

        LOG.debug("commence() X-Requested-With={}", ajaxRequestHeader);

        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Frame-Options", "DENY");

        String jsonResp = "";

        try {
            String     msg        = exception.getMessage();
            VXResponse vXResponse = new VXResponse();

            if (msg != null && !msg.isEmpty()) {
                if (CLIUtil.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", request).equalsIgnoreCase(msg)) {
                    vXResponse.setStatusCode(HttpServletResponse.SC_UNAUTHORIZED);
                    vXResponse.setMsgDesc("The username or password you entered is incorrect.");

                    LOG.info("Error Message : {}", msg);
                } else if (msg.contains("Could not get JDBC Connection; nested exception is java.sql.SQLException: Connections could not be acquired from the underlying database!")) {
                    vXResponse.setStatusCode(HttpServletResponse.SC_UNAUTHORIZED);
                    vXResponse.setMsgDesc("Unable to connect to DB.");
                } else if (msg.contains("Communications link failure")) {
                    vXResponse.setStatusCode(HttpServletResponse.SC_UNAUTHORIZED);
                    vXResponse.setMsgDesc("Unable to connect to DB.");
                } else if (CLIUtil.getMessage("AbstractUserDetailsAuthenticationProvider.disabled", request).equalsIgnoreCase(msg)) {
                    vXResponse.setStatusCode(HttpServletResponse.SC_UNAUTHORIZED);
                    vXResponse.setMsgDesc("The username or password you entered is disabled.");
                } else if (CLIUtil.getMessage("AbstractUserDetailsAuthenticationProvider.locked", request).equalsIgnoreCase(msg)) {
                    vXResponse.setStatusCode(HttpServletResponse.SC_UNAUTHORIZED);
                    vXResponse.setMsgDesc("The user account is locked.");
                }
            }

            jsonResp = jsonUtil.writeObjectAsString(vXResponse);

            response.getWriter().write(jsonResp);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } catch (IOException e) {
            LOG.info("Error while writing JSON in HttpServletResponse");
        }

        if ("XMLHttpRequest".equalsIgnoreCase(ajaxRequestHeader)) {
            LOG.debug("Sending login failed response : {}", jsonResp);
        }
    }
}
