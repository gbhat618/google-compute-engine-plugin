package com.google.jenkins.plugins.computeengine.ui.helpers;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

@SuppressWarnings("unused")
public class SpotVm extends ProvisioningType {

    @DataBoundConstructor
    public SpotVm() {
        super(ProvisioningTypeValue.SPOT);
    }

    @Extension
    public static class DescriptorImpl extends ProvisioningTypeDescriptor {
        @Override
        public String getDisplayName() {
            return "Spot VM";
        }

        @Override
        public ProvisioningTypeValue getProvisioningTypeValue() {
            return ProvisioningTypeValue.SPOT;
        }
    }
}
