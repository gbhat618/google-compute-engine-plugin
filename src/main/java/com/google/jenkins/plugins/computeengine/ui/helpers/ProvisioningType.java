/*
 * Copyright 2024 CloudBees, Inc.
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

package com.google.jenkins.plugins.computeengine.ui.helpers;

import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * ProvisioningType represents the type of VM to be provisioned.
 */
public abstract class ProvisioningType extends AbstractDescribableImpl<ProvisioningType> {

    private ProvisioningTypeValue value;

    public ProvisioningType(ProvisioningTypeValue value) {
        this.value = value;
    }

    public ProvisioningTypeValue getValue() {
        return value;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setProvisioningTypeValue(ProvisioningTypeValue value) {
        this.value = value;
    }

    public abstract static class ProvisioningTypeDescriptor extends Descriptor<ProvisioningType> {}
}
