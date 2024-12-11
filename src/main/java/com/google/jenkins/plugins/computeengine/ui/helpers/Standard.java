package com.google.jenkins.plugins.computeengine.ui.helpers;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

@SuppressWarnings("unused")
public class Standard extends ProvisioningType {

    @DataBoundConstructor
    public Standard() {
        super(ProvisioningTypeValue.STANDARD);
    }

    @Extension
    public static class DescriptorImpl extends ProvisioningTypeDescriptor {
        @Override
        public String getDisplayName() {
            return "Standard";
        }

        @Override
        public ProvisioningTypeValue getProvisioningTypeValue() {
            return ProvisioningTypeValue.STANDARD;
        }
    }
}
