/**
  Arguments:
  * system: matrix system for build (eg `aarch64-darwin`, `x86_64-linux`)
  * nixArgs: Extra nix arguments for nix commands
  * cachix: (Optional) Cachix configuration required for `cachix-push`
      cache: Cachix account for cache pushing
      prefix: Prefix for cachix pinning
      names: (Optional) List of derivation names to do whitelisted cachix push & pin
  * docker: (Optional) Docker configuration required for pushing `docker image` for deployments
      flakeOutput: Flake output for docker image
      imageName: Name of docker image
      imageTag: Tag for the docker image
      awsAccounts: List of AWS deployment targets
        name: Environment name
        ns: Namespace
        awsAccountId: AWS account ID
        region: AWS region
*/
def run(Map params) {

  // Default values
   def defaults = [
      resultFilePrefix: 'omci',
      subflake: 'ROOT',
      dockerImageTarballName: 'dockerImage.tar.gz',
   ]

   // Merge provided params with defaults
   def config = defaults + params

   //Build all flake outputs & Push to Cachix
   def omciResultJson = omnixStage(config)

   // Push & Pin paths to Cachix
   if (config.cachix) {
     cachixStage(config, omciResultJson)
   }

   //Perform docker operations
   if (config.system == "x86_64-linux" && config.docker) {
     dockerStage(config.docker, config.dockerImageTarballName)
   }
}

/**
 * Runs the CI build and handles cachix operations
 */
def omnixStage(config){

    stage("Build all flake outputs ❄️"){

      // Check for required parameters
      if (!config.system) {
         error "system is required and cannot be empty"
       }

      def omciResultJson = "${config.resultFilePrefix}-${config.system}.json"

      def omci_args = [
        "--systems ${config.system}",
        "--results=${omciResultJson}"
      ]
      
      // Append nix arguments if any to be passed to `om ci run` command
      if (config.nixArgs) {
        omci_args += ["--"]
        omci_args += config.nixArgs
      }

      // Build flake outputs
      sh "om ci run ${omci_args.join(' ')}"

      return omciResultJson
   }
}

/**
 * Handles Cachix Operations like pushing and pinning /nix/store paths
 */
def cachixStage(config, String omciResultJson){
  stage("Push to Cachix"){

   // Check for required parameters
   if (!config.cachix.prefix) {
     error "prefix is required and cannot be empty"
   }
   if (!config.cachix.cache) {
     error "cache is required and cannot be empty"
   }

   // Pass `--names` argument to do a whitelisted push and pin
   // If you need to push everything (all derivations), remove the argument `names` from below.
   sh """
      nix run github:juspay/cachix-push -- \
        --subflake ${config.subflake} \
        --prefix ${config.cachix.prefix} \
        --cache ${config.cachix.cache} \
        --names '${config.cachix.names}' \
        < ${omciResultJson}
    """
  }
}

/**
 * Handles Docker operations including building and pushing
 */
def dockerStage(dockerConfig, String dockerImageTarballName){

  stage("Docker Stage"){

    // Check for required parameters
    validateDockerConfigParams(dockerConfig)

    buildDockerImage(dockerConfig.flakeOutput, dockerImageTarballName)

    stashDockerImage(dockerConfig.imageTag, dockerImageTarballName)

    awsAgentStage(dockerConfig, dockerImageTarballName)

  }
}

/**
 * Builds and saves tarball of docker image using `nix2container` (https://github.com/nlewo/nix2container)
 */
def buildDockerImage(String flakeOutput, String dockerImageTarballName) {
  return sh(script: "nix run .#${flakeOutput}.copyTo 'docker-archive:${dockerImageTarballName}'")
}

/**
 * Stashes Docker image and returns relevant information
 */
def stashDockerImage(String imageTag, String dockerImageTarballName) {
    stash includes: "${dockerImageTarballName}", name: imageTag, allowEmpty: false
    /**
     We need to remove the tarball because it stays in locally cloned repo,
     since we copy the tarball here
     Check `buildDockerImage` function for more information, 
     so if you retrigger the build in the same pipeline
     `buildDockerImage` function will throw error `docker-archive doesn't support modifying existing images`
    */
    sh "rm ${dockerImageTarballName}" 
}

/**
  Switch to `aws` based jenkins agent to push docker image to `aws registry`
*/
def awsAgentStage(dockerConfig, String dockerImageTarballName){
  // Use the label 'euler-nix' for agent selection
  node(label: 'euler-nix') {
    try {
      unstash "${dockerConfig.imageTag}"
      // Load the docker image and capture Image ID
      def imageId = sh(script: "docker load < ${dockerImageTarballName} | awk '{print \$NF}'", returnStdout: true).trim()

      // Iterate through awsAccounts and perform push operations
      for (def env in dockerConfig.awsAccounts) {
        stage("Docker Push ${env.name}") {
          authenticateAndPushImage(imageId, dockerConfig, env.ns, env.awsAccountId, env.region)
        }
      }

    } catch (Exception e) {
      currentBuild.result = 'FAILURE'
      throw e
    }
  }
}

/**
 * Pushes Docker image to ECR
 */
def authenticateAndPushImage(String imageId, dockerConfig, String ns, String awsAccountId, String region) {
  sh "aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${awsAccountId}.dkr.ecr.${region}.amazonaws.com"
  sh "docker tag ${imageId} ${ns}/${dockerConfig.imageName}:${dockerConfig.imageTag}"
  sh "docker push ${ns}/${dockerConfig.imageName}:${dockerConfig.imageTag}"
}

/**
 * Validates Docker configuration
 */
def validateDockerConfigParams(config){
  if (!config.flakeOutput){
    error "flakeOutput is required and cannot be empty"
  }
  if (!config.imageName){
    error "imageName is required and cannot be empty"
  }
  if (!config.imageTag){
    error "imageTag is required and cannot be empty"
  }
  if (!config.awsAccounts){
    error "awsAccounts is required and cannot be empty"
  }
}

return this
