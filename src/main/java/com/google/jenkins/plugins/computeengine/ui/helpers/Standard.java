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
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

@SuppressWarnings("unused")
public class Standard extends ProvisioningType {

    @DataBoundSetter
    private long maxRunDurationSeconds;

    @DataBoundConstructor
    public Standard() {
        super(ProvisioningTypeValue.STANDARD);
    }

    public void setMaxRunDurationSeconds(long maxRunDurationSeconds) {
        this.maxRunDurationSeconds = maxRunDurationSeconds;
    }

    public long getMaxRunDurationSeconds() {
        return maxRunDurationSeconds;
    }

    @Extension
    public static class DescriptorImpl extends ProvisioningTypeDescriptor {
        @Override
        public String getDisplayName() {
            return "Standard";
        }

        public FormValidation doCheckMaxRunDurationSeconds(@QueryParameter String value) {
            return Utils.doCheckMaxRunDurationSeconds(value);
        }
    }
}
