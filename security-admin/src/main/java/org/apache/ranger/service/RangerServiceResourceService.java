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

package org.apache.ranger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.common.SearchField;
import org.apache.ranger.common.SearchField.DATA_TYPE;
import org.apache.ranger.common.SearchField.SEARCH_TYPE;
import org.apache.ranger.common.SortField;
import org.apache.ranger.entity.XXServiceResource;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.util.SearchFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RangerServiceResourceService extends RangerServiceResourceServiceBase<XXServiceResource, RangerServiceResource> {
    private static final Logger LOG = LoggerFactory.getLogger(RangerServiceResourceService.class);

    public static final TypeReference<Map<String, RangerPolicy.RangerPolicyResource>> subsumedDataType   = new TypeReference<Map<String, RangerPolicy.RangerPolicyResource>>() {};
    public static final TypeReference<List<RangerTag>>                                duplicatedDataType = new TypeReference<List<RangerTag>>() {};

    private boolean serviceUpdateNeeded = true;

    public RangerServiceResourceService() {
        searchFields.add(new SearchField(SearchFilter.TAG_RESOURCE_ID, "obj.id", DATA_TYPE.INTEGER, SEARCH_TYPE.FULL));
        searchFields.add(new SearchField(SearchFilter.TAG_SERVICE_ID, "obj.serviceId", DATA_TYPE.INTEGER, SEARCH_TYPE.FULL));
        searchFields.add(new SearchField(SearchFilter.TAG_SERVICE_NAME, "service.name", DATA_TYPE.STRING, SEARCH_TYPE.FULL, "XXService service", "obj.serviceId = service.id"));
        searchFields.add(new SearchField(SearchFilter.TAG_SERVICE_NAME_PARTIAL, "service.name", DATA_TYPE.STRING, SEARCH_TYPE.PARTIAL, "XXService service", "obj.serviceId = service.id"));
        searchFields.add(new SearchField(SearchFilter.TAG_RESOURCE_GUID, "obj.guid", DATA_TYPE.STRING, SEARCH_TYPE.FULL));
        searchFields.add(new SearchField(SearchFilter.TAG_RESOURCE_SIGNATURE, "obj.resourceSignature", DATA_TYPE.STRING, SEARCH_TYPE.FULL));
        searchFields.add(new SearchField(SearchFilter.TAG_RESOURCE_IDS, "obj.id", SearchField.DATA_TYPE.INT_LIST, SearchField.SEARCH_TYPE.FULL));

        sortFields.add(new SortField(SearchFilter.TAG_RESOURCE_ID, "obj.id", true, SortField.SORT_ORDER.ASC));
        sortFields.add(new SortField(SearchFilter.TAG_SERVICE_ID, "obj.serviceId"));
        sortFields.add(new SortField(SearchFilter.CREATE_TIME, "obj.createTime"));
        sortFields.add(new SortField(SearchFilter.UPDATE_TIME, "obj.updateTime"));
    }

    @Override
    public RangerServiceResource postUpdate(XXServiceResource resource) {
        RangerServiceResource ret = super.postUpdate(resource);

        if (serviceUpdateNeeded) {
            daoMgr.getXXServiceVersionInfo().updateServiceVersionInfoForServiceResourceUpdate(resource.getId());
        }

        return ret;
    }

    @Override
    protected void validateForCreate(RangerServiceResource vObj) {
    }

    @Override
    protected void validateForUpdate(RangerServiceResource vObj, XXServiceResource entityObj) {
        serviceUpdateNeeded = !StringUtils.equals(entityObj.getGuid(), vObj.getGuid()) || !StringUtils.equals(entityObj.getResourceSignature(), vObj.getResourceSignature());
    }

    public RangerServiceResource getPopulatedViewObject(XXServiceResource xObj) {
        return populateViewBean(xObj);
    }

    public RangerServiceResource getServiceResourceByGuid(String guid) {
        RangerServiceResource ret               = null;
        XXServiceResource     xxServiceResource = daoMgr.getXXServiceResource().findByGuid(guid);

        if (xxServiceResource != null) {
            ret = populateViewBean(xxServiceResource);
        }

        return ret;
    }

    public List<RangerServiceResource> getByServiceId(Long serviceId) {
        List<RangerServiceResource> ret                = new ArrayList<>();
        List<XXServiceResource>     xxServiceResources = daoMgr.getXXServiceResource().findByServiceId(serviceId);

        if (CollectionUtils.isNotEmpty(xxServiceResources)) {
            for (XXServiceResource xxServiceResource : xxServiceResources) {
                RangerServiceResource serviceResource = populateViewBean(xxServiceResource);

                ret.add(serviceResource);
            }
        }

        return ret;
    }

    public RangerServiceResource getByServiceAndResourceSignature(Long serviceId, String resourceSignature) {
        RangerServiceResource ret               = null;
        XXServiceResource     xxServiceResource = daoMgr.getXXServiceResource().findByServiceAndResourceSignature(serviceId, resourceSignature);

        if (xxServiceResource != null) {
            ret = populateViewBean(xxServiceResource);
        }

        return ret;
    }

    public List<RangerServiceResource> getTaggedResourcesInServiceId(Long serviceId) {
        List<RangerServiceResource> ret                = new ArrayList<>();
        List<XXServiceResource>     xxServiceResources = daoMgr.getXXServiceResource().findByServiceId(serviceId);

        if (CollectionUtils.isNotEmpty(xxServiceResources)) {
            for (XXServiceResource xxServiceResource : xxServiceResources) {
                RangerServiceResource serviceResource = populateViewBean(xxServiceResource);

                ret.add(serviceResource);
            }
        }

        return ret;
    }

    @Override
    protected XXServiceResource mapViewToEntityBean(RangerServiceResource serviceResource, XXServiceResource xxServiceResource, int operationContext) {
        XXServiceResource ret = super.mapViewToEntityBean(serviceResource, xxServiceResource, operationContext);

        if (MapUtils.isNotEmpty(serviceResource.getResourceElements())) {
            String serviceResourceElements = JsonUtils.mapToJson(serviceResource.getResourceElements());

            if (StringUtils.isNotEmpty(serviceResourceElements)) {
                ret.setServiceResourceElements(serviceResourceElements);
            } else {
                LOG.info("Empty string representing serviceResourceElements in [{}]!!", ret);
            }
        }

        return ret;
    }

    @Override
    protected RangerServiceResource mapEntityToViewBean(RangerServiceResource serviceResource, XXServiceResource xxServiceResource) {
        RangerServiceResource ret = super.mapEntityToViewBean(serviceResource, xxServiceResource);

        if (StringUtils.isNotEmpty(xxServiceResource.getServiceResourceElements())) {
            Map<String, RangerPolicy.RangerPolicyResource> serviceResourceElements = null;

            try {
                serviceResourceElements = JsonUtils.jsonToObject(xxServiceResource.getServiceResourceElements(), RangerServiceResourceService.subsumedDataType);
            } catch (JsonProcessingException e) {
                LOG.error("Error occurred while processing JSON  ", e);
            }

            if (MapUtils.isNotEmpty(serviceResourceElements)) {
                ret.setResourceElements(serviceResourceElements);
            } else {
                LOG.info("Empty serviceResourceElement in [{}]!!", ret);
            }
        } else {
            LOG.info("Empty string representing serviceResourceElements in [{}]!!", xxServiceResource);
        }

        return ret;
    }

    @Override
    Map<String, RangerPolicy.RangerPolicyResource> getServiceResourceElements(XXServiceResource xxServiceResource) {
        return new HashMap<>();
    }
}
