(ns verybigthings.cdk-clj-templates.pasta-xiana.backend
  (:require [verybigthings.cdk :as cdk]
            [clojure.spec.alpha :as s]))

(cdk/import [[Stack Environment CfnOutput Duration SecretValue] :from "core"]
            [[ARecord RecordTarget HostedZone HostedZoneAttributes] :from "route53"]
            [[LoadBalancerTarget] :from "route53.targets"]
            [[ApplicationTargetGroup HealthCheck] :from "elasticloadbalancingv2"]
            [[Vpc VpcLookupOptions SubnetSelection SubnetType SecurityGroup Peer Port InstanceType InstanceClass InstanceSize] :from "ec2"]
            [[Cluster ContainerImage] :from "ecs"]
            [[Repository] :from "ecr"]
            [[ApplicationLoadBalancedTaskImageOptions ApplicationLoadBalancedFargateService] :from "ecs.patterns"]
            [[DatabaseInstance DatabaseInstanceEngine Credentials] :from "rds"])

(s/def :config/name string?)
(s/def :config/region string?)
(s/def :config/account string?)
(s/def :config/repo-name string?)
(s/def :config/db-name string?)
(s/def :config/jwt-secret string?)
(s/def :config/subdomain string?)

(s/def ::config
  (s/keys :req [:config/name :config/region :config/account :config/repo-name :config/db-name :config/jwt-secret :config/subdomain]))

(def commitHash (System/getenv "GITHUB_SHA"))
(def domain "vbt.guru")
(def hosted-zone-id "Z01486621H5A62OYXMUNK")
(def vpc-id "vpc-0b551c37507cef61b")

(defn- create-id [text {:config/keys [name]}]
  (str name "-" text))

(defn- get-db-url [rds]
  (let [url (cdk/get rds :dbInstanceEndpointAddress)
        secret (.unsafeUnwrap (.secretValueFromJson (cdk/get rds [:secret]) "password"))
        dbname (.unsafeUnwrap (.secretValueFromJson (cdk/get rds [:secret]) "dbname"))]
    (str "postgres://postgres:" secret "@" url ":5432/" dbname)))

(defn- InitializeStack [app {:config/keys [name region account]}]
  (Stack app name {:env (Environment {:region region
                                      :account account})}))

(defn- InitializeRepo [stack {:config/keys [repo-name] :as config}]
  (Repository/fromRepositoryName stack (create-id "repo" config) repo-name))

(defn- InitializeVpc [stack config]
  (Vpc/fromLookup stack (create-id "vpc" config) (VpcLookupOptions {:vpcId vpc-id})))

(defn- InitializeSecurityGroupDB [stack vpc {:config/keys [name]}]
  (let [security-group (SecurityGroup stack (str name "-securityGroupDB") {:vpc vpc})]
    (SecurityGroup/addIngressRule
     security-group
     (Peer/ipv4 "0.0.0.0/0")
     (Port/tcp 5432))
    security-group))

(defn- InitializeDB [stack vpc {:config/keys [db-name] :as config}]
  (DatabaseInstance stack (create-id "db" config) {:vpc vpc
                                                   :vpcSubnets (SubnetSelection {:subnetType SubnetType/PUBLIC})
                                                   :engine (DatabaseInstanceEngine/POSTGRES)
                                                   :databaseName db-name
                                                   :credentials (Credentials/fromGeneratedSecret "postgres")
                                                   :securityGroups [(InitializeSecurityGroupDB stack vpc config)]
                                                   :instanceType (InstanceType/of InstanceClass/T3 InstanceSize/MICRO)}))

(defn- InitializeCluster [stack vpc config]
  (Cluster stack (create-id "cluster" config) {:vpc vpc}))

(defn- TaskImageOptions [repo rds {:config/keys [jwt-secret]}]
  (ApplicationLoadBalancedTaskImageOptions
   {:image (ContainerImage/fromEcrRepository repo commitHash)
    :containerPort 3000
    :environment (doto
                  (java.util.HashMap.)
                   (.put "PG_URL" (get-db-url rds))
                   (.put "JWT_SECRET" jwt-secret)
                   (.put "WS_PORT" "3000"))}))

(defn- InitializeAlbFs [stack cluster repo rds config]
  (ApplicationLoadBalancedFargateService
   stack
   (create-id "fargate" config)
   {:cluster cluster
    :desiredCount 1
    :taskImageOptions (TaskImageOptions repo rds config)
    :memoryLimitMiB 2048
    :assignPublicIp true
    :publicLoadBalancer true}))

(defn- InitializeARecord [stack albfs {:config/keys [subdomain] :as config}]
  (ARecord stack (create-id "arecord" config) {:recordName (str subdomain "." domain)
                                               :zone (HostedZone/fromHostedZoneAttributes stack (create-id "zone" config) (HostedZoneAttributes {:hostedZoneId hosted-zone-id
                                                                                                                                                 :zoneName domain}))
                                               :target (RecordTarget/fromAlias (LoadBalancerTarget (cdk/get albfs :loadBalancer)))}))
(defn- Initialize [app config]
  (let [stack (InitializeStack app config)
        repo (InitializeRepo stack config)
        vpc (InitializeVpc stack config)
        rds (InitializeDB stack vpc config)
        cluster (InitializeCluster stack vpc config)
        albfs (InitializeAlbFs stack cluster repo rds config)]
    (InitializeARecord stack albfs config)
    (->
     (ApplicationLoadBalancedFargateService/getTargetGroup albfs)
     (ApplicationTargetGroup/configureHealthCheck (HealthCheck {:timeout (Duration/seconds 120)
                                                                :interval (Duration/seconds 180)
                                                                :healthyThresholdCount 2})))))

(defn PastaXianaBackendStack "
  Provision AWS ready pasta xiana backend stack
  
  Arguments:
  :config/name -> Stack name
  :config/region -> Region name needed to connect to the existing VPC
  :config/account -> Account id needed to connect to the existing VPC
  :config/repo-name -> ECR repository name used to extract latest docker image
  :config/db-name -> Database name do be used
  :config/jwt-secret
  :config/subdomain -> Subdomain that will be dynamically created
  "
  [app config]
  (if (s/valid? ::config config)
    (Initialize app config)
    (throw (ex-info
            (s/explain-str ::config config)
            (s/explain-data ::config config)))))
