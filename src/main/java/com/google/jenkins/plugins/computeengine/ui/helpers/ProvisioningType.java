package com.google.jenkins.plugins.computeengine.ui.helpers;

import org.kohsuke.stapler.DataBoundSetter;

import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

/**
 * ProvisioningType represents the type of VM to be provisioned.
 */
public abstract class ProvisioningType extends AbstractDescribableImpl<ProvisioningType> {
    public ProvisioningTypeValue value;

    public ProvisioningType(ProvisioningTypeValue value) {
        this.value = value;
    }

    public ProvisioningTypeValue getValue() {
        return value;
    }

    @DataBoundSetter
    public void setProvisioningTypeValue(ProvisioningTypeValue value) {
        this.value = value;
    }

    public abstract static class ProvisioningTypeDescriptor extends Descriptor<ProvisioningType> {
        public abstract ProvisioningTypeValue getProvisioningTypeValue();
    }
}
