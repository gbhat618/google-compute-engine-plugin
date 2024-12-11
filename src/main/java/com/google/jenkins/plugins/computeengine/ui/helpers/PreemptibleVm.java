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

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

@SuppressWarnings("unused")
public class PreemptibleVm extends ProvisioningType {

    @DataBoundConstructor
    public PreemptibleVm() {
        super(ProvisioningTypeValue.PREEMPTIBLE);
    }

    @Extension
    public static class DescriptorImpl extends ProvisioningTypeDescriptor {
        @Override
        public String getDisplayName() {
            return "Preemptible VM";
        }

        @Override
        public ProvisioningTypeValue getProvisioningTypeValue() {
            return ProvisioningTypeValue.PREEMPTIBLE;
        }
    }
}
