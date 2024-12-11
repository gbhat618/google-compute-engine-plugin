package com.google.jenkins.plugins.computeengine.ui.helpers;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

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
