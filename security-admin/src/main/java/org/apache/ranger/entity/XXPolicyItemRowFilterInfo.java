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
package org.apache.ranger.entity;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import java.util.Objects;

@Entity
@Cacheable
@Table(name = "x_policy_item_rowfilter")
public class XXPolicyItemRowFilterInfo extends XXDBBase implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * id of the XXPolicyItemRowFilterInfo
     * <ul>
     * </ul>
     */
    @Id
    @SequenceGenerator(name = "x_policy_item_rowfilter_SEQ", sequenceName = "x_policy_item_rowfilter_SEQ", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "x_policy_item_rowfilter_SEQ")
    @Column(name = "id")
    protected Long id;

    /**
     * policyItemId of the XXPolicyItemRowFilterInfo
     * <ul>
     * </ul>
     */
    @Column(name = "policy_item_id")
    protected Long policyItemId;

    /**
     * filter_expr of the XXPolicyItemRowFilterInfo
     * <ul>
     * </ul>
     */
    @Column(name = "filter_expr")
    protected String filterExpr;

    /**
     * Returns the value for the member attribute <b>id</b>
     *
     * @return Long - value of member attribute <b>id</b> .
     */
    public Long getId() {
        return this.id;
    }

    /**
     * This method sets the value to the member attribute <b> id</b> . You
     * cannot set null to the attribute.
     *
     * @param id Value to set member attribute <b> id</b>
     */
    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (!super.equals(obj)) {
            return false;
        }

        XXPolicyItemRowFilterInfo other = (XXPolicyItemRowFilterInfo) obj;

        return Objects.equals(id, other.id) &&
                Objects.equals(filterExpr, other.filterExpr) &&
                Objects.equals(policyItemId, other.policyItemId);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "XXPolicyItemDataMaskInfo [" + super.toString() + " id=" + id + ", policyItemId=" + policyItemId + ", filterExpr=" + filterExpr + "]";
    }

    /**
     * Returns the value for the member attribute <b>policyItemId</b>
     *
     * @return Long - value of member attribute <b>policyItemId</b> .
     */
    public Long getPolicyItemId() {
        return this.policyItemId;
    }

    /**
     * This method sets the value to the member attribute <b> policyItemId</b> .
     * You cannot set null to the attribute.
     *
     * @param policyItemId Value to set member attribute <b> policyItemId</b>
     */
    public void setPolicyItemId(Long policyItemId) {
        this.policyItemId = policyItemId;
    }

    /**
     * Returns the value for the member attribute <b>filterExpr</b>
     *
     * @return String - value of member attribute <b>filterExpr</b> .
     */
    public String getFilterExpr() {
        return this.filterExpr;
    }

    /**
     * This method sets the value to the member attribute <b> filterExpr</b> .
     * You cannot set null to the attribute.
     *
     * @param filterExpr Value to set member attribute <b> filterExpr</b>
     */
    public void setFilterExpr(String filterExpr) {
        this.filterExpr = filterExpr;
    }
}
