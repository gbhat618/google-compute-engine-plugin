# Integration Tests

* GCP Project
* Create a service account with relevant access - See [Refer to IAM Credentials](Home.md#iam-credentials)
* Most of the tests require a VM with Java pre-installed at `/usr/bin/java`.
  There are one or two which don't require java pre-installed, as they supply `startup-script` to gcloud apis, and install `java` on a plain linux Debian image.
  * You can create an image with `java` preinstalled as,
    ```bash
    project=<your-project>
    zone=<your-zone>
    
    # Create a debian based VM
    gcloud compute instances create java-install-instance \
	--project=$project \
    --zone=$zone \
    --machine-type=e2-medium \
    --image-project=debian-cloud \
    --image-family=debian-12
    
    # Wait for the machine to start and access ssh connections. Install java via ssh
    gcloud compute ssh java-install-instance \
	--project=$project \
    --zone=$zone \
    --command="sudo apt-get update && sudo apt-get install -y openjdk-17-jdk"
    
    # Ensure java is installed and print the java path
    gcloud compute ssh java-install-instance \
	--project=$project \
    --zone=$zone \
    --command="java -version"
    
    gcloud compute ssh java-install-instance \
	--project=$project \
    --zone=$zone \
    --command="which java"
    
    # For creating image, you need to first stop the VM
    gcloud compute instances stop java-install-instance \
	--project=$project \
    --zone=$zone
    
    # Create an image from the VM
    gcloud compute images create java-debian-12-image \
    --source-disk=java-install-instance \
    --source-disk-zone=$zone \
    --project=$project \
    --family=custom-java-debian-family
    
    # Delete the VM
    gcloud compute instances delete java-install-instance \
    --project=$project \
    --zone=$zone
    ```
* Export these environment variables
  ```bash
  export GOOGLE_PROJECT_ID=<your-project>
  export GOOGLE_SA_NAME=<name of the SA created in first step>
  export GOOGLE_CREDENTIALS_FILE=<full path to the SA JSON file>
  export GOOGLE_ZONE=<your-compute-zone>
  export GOOGLE_REGION=<your-compute-region>
  export GOOGLE_BOOT_DISK_PROJECT_ID=<your-project>
  export GOOGLE_BOOT_DISK_IMAGE_NAME=java-debian-12-image # this is created in previous step
  ```
* Execute an integration test (example)
  ```bash
  mvn clean test -Dtest=ComputeEngineCloudRestartPreemptedIT#testIfNodeWasPreempted
  ```
