/*
 *     Copyright 2015-2016 Jean-Christophe Sirot <sirot@chelonix.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.ansible.workflow;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Project;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ansible.AnsibleInstallation;
import org.jenkinsci.plugins.ansible.AnsiblePlaybookBuilder;
import org.jenkinsci.plugins.ansible.ExtraVar;
import org.jenkinsci.plugins.ansible.Inventory;
import org.jenkinsci.plugins.ansible.InventoryPath;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

/**
 * The Ansible playbook invocation step for the Jenkins workflow plugin.
 */
public class AnsiblePlaybookStep extends AbstractStepImpl {

    private final String playbook;
    private String inventory;
    private String installation;
    private String credentialsId;
    private boolean sudo = false;
    private String sudoUser = "root";
    private String limit = null;
    private String tags = null;
    private String skippedTags = null;
    private String startAtTask = null;
    private Map extraVars = null;
    private String extras = null;
    private boolean colorized = false;
    private int forks = 5;

    @DataBoundConstructor
    public AnsiblePlaybookStep(String playbook) {
        this.playbook = playbook;
    }

    @DataBoundSetter
    public void setInventory(String inventory) {
        this.inventory = Util.fixEmptyAndTrim(inventory);
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    @DataBoundSetter
    public void setSudo(boolean sudo) {
        this.sudo = sudo;
    }

    @DataBoundSetter
    public void setSudoUser(String sudoUser) {
        this.sudoUser = Util.fixEmptyAndTrim(sudoUser);
    }

    @DataBoundSetter
    public void setInstallation(String installation) {
        this.installation = Util.fixEmptyAndTrim(installation);
    }

    @DataBoundSetter
    public void setLimit(String limit) {
        this.limit = Util.fixEmptyAndTrim(limit);
    }

    @DataBoundSetter
    public void setTags(String tags) {
        this.tags = Util.fixEmptyAndTrim(tags);
    }

    @DataBoundSetter
    public void setSkippedTags(String skippedTags) {
        this.skippedTags = Util.fixEmptyAndTrim(skippedTags);
    }

    @DataBoundSetter
    public void setStartAtTask(String startAtTask) {
        this.startAtTask = Util.fixEmptyAndTrim(startAtTask);
    }

    @DataBoundSetter
    public void setExtraVars(Map extraVars) {
        this.extraVars = extraVars;
    }

    @DataBoundSetter
    public void setExtras(String extras) {
        this.extras = Util.fixEmptyAndTrim(extras);
    }

    @DataBoundSetter
    public void setColorized(boolean colorized) {
        this.colorized = colorized;
    }

    @DataBoundSetter
    public void setForks(int forks) {
        this.forks = forks;
    }

    public String getInstallation() {
        return installation;
    }

    public String getPlaybook() {
        return playbook;
    }

    public String getInventory() {
        return inventory;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean isSudo() {
        return sudo;
    }

    public String getSudoUser() {
        return sudoUser;
    }

    public String getLimit() {
        return limit;
    }

    public String getTags() {
        return tags;
    }

    public String getSkippedTags() {
        return skippedTags;
    }

    public String getStartAtTask() {
        return startAtTask;
    }

    public Map<String, Object> getExtraVars() {
        return extraVars;
    }

    public String getExtras() {
        return extras;
    }

    public boolean isColorized() {
        return colorized;
    }

    public int getForks() {
        return forks;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(AnsiblePlaybookExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "ansiblePlaybook";
        }

        @Override
        public String getDisplayName() {
            return "Invoke an ansible playbook";
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Project project) {
            return new StandardListBoxModel()
                .withEmptySelection()
                .withMatching(anyOf(
                    instanceOf(SSHUserPrivateKey.class),
                    instanceOf(UsernamePasswordCredentials.class)),
                    CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, project));
        }

        public ListBoxModel doFillInstallationItems() {
            ListBoxModel model = new ListBoxModel();
            for (AnsibleInstallation tool : AnsibleInstallation.allInstallations()) {
                model.add(tool.getName());
            }
            return model;
        }
    }

    public static final class AnsiblePlaybookExecution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1;

        @Inject
        private transient AnsiblePlaybookStep step;

        @StepContextParameter
        private transient TaskListener listener;

        @StepContextParameter
        private transient Launcher launcher;

        @StepContextParameter
        private transient Run<?,?> run;

        @StepContextParameter
        private transient FilePath ws;

        @StepContextParameter
        private transient EnvVars envVars;

        @StepContextParameter
        private transient Computer computer;

        private List<ExtraVar> convertExtraVars(Map<String, Object> extraVars) {
            if (extraVars == null) {
                return null;
            }
            List<ExtraVar> extraVarList = new ArrayList<ExtraVar>();
            for (String key: extraVars.keySet()) {
                ExtraVar var = new ExtraVar();
                var.setKey(key);
                Object o = extraVars.get(key);
                if (o instanceof Map) {
                    var.setValue(((Map)o).get("value").toString());
                    var.setHidden((Boolean)((Map)o).get("hidden"));
                } else {
                    var.setValue(o.toString());
                    var.setHidden(false);
                }
                extraVarList.add(var);
            }
            return extraVarList;
        }

        @Override
        protected Void run() throws Exception {
            Inventory inventory = StringUtils.isNotBlank(step.getInventory()) ? new InventoryPath(step.getInventory()) : null;
            AnsiblePlaybookBuilder builder = new AnsiblePlaybookBuilder(step.getPlaybook(), inventory);
            builder.setAnsibleName(step.getInstallation());
            builder.setSudo(step.isSudo());
            builder.setSudoUser(step.getSudoUser());
            builder.setCredentialsId(step.getCredentialsId(), true);
            builder.setForks(step.getForks());
            builder.setLimit(step.getLimit());
            builder.setTags(step.getTags());
            builder.setStartAtTask(step.getStartAtTask());
            builder.setSkippedTags(step.getSkippedTags());
            builder.setExtraVars(convertExtraVars(step.extraVars));
            builder.setAdditionalParameters(step.getExtras());
            builder.setHostKeyChecking(false);
            builder.setUnbufferedOutput(true);
            builder.setColorizedOutput(step.isColorized());
            builder.perform(run, computer.getNode(), ws, launcher, listener, envVars);
            return null;
        }
    }

}
