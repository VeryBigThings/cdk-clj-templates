(ns verybigthings.cdk-clj-templates.pasta-xiana.backend
  (:require [verybigthings.cdk :as cdk]))

(cdk/import [[Stack Environment CfnOutput Duration SecretValue] :from "core"]
            [[ARecord RecordTarget HostedZone HostedZoneAttributes] :from "route53"]
            [[LoadBalancerTarget] :from "route53.targets"]
            [[ApplicationTargetGroup HealthCheck] :from "elasticloadbalancingv2"]
            [[Vpc VpcLookupOptions SubnetSelection SubnetType] :from "ec2"]
            [[Cluster ContainerImage] :from "ecs"]
            [[Repository] :from "ecr"]
            [[ApplicationLoadBalancedTaskImageOptions ApplicationLoadBalancedFargateService] :from "ecs.patterns"]
            [[DatabaseInstance DatabaseInstanceEngine Credentials] :from "rds"])

(def commitHash (System/getenv "GITHUB_SHA"))
(def domain "vbt.guru")
(def hosted-zone-id "Z01486621H5A62OYXMUNK")
(def vpc-id "vpc-0b551c37507cef61b")

(defn get-db-url [rds]
  (let [url (cdk/get rds :dbInstanceEndpointAddress)
        secret (.unsafeUnwrap (.secretValueFromJson (cdk/get rds [:secret]) "password"))
        dbname (.unsafeUnwrap (.secretValueFromJson (cdk/get rds [:secret]) "dbname"))]
    (str "postgres://postgres:" secret "@" url ":5432/" dbname)))

(defn- TaskImageOptions [repo rds]
  (ApplicationLoadBalancedTaskImageOptions
   ;{:image (ContainerImage/fromEcrRepository repo commitHash)
   {:image (ContainerImage/fromEcrRepository repo)
    :containerPort 3000
    :environment (doto
                  (java.util.HashMap.)
                   (.put "PG_URL" (get-db-url rds))
                   (.put "JWT_SECRET" "secret7")
                   (.put "WS_PORT" "3000"))}))

(defn- InitializeStack [app {:keys [name region account]}]
  (Stack app name {:env (Environment {:region region
                                      :account account})}))

(defn- InitializeRepo [stack {:keys [name repo-name]}]
  (Repository/fromRepositoryName stack (str name "-repo") repo-name))

(defn- InitializeVpc [stack {:keys [name]}]
  (Vpc/fromLookup stack (str name "-vpc") (VpcLookupOptions {:vpcId vpc-id})))

(defn- InitializeDB [stack vpc {:keys [name db-name]}]
  (DatabaseInstance stack (str name "-db") {:vpc vpc
                                            :vpcSubnets (SubnetSelection {:subnetType SubnetType/PUBLIC})
                                            :engine (DatabaseInstanceEngine/POSTGRES)
                                            :databaseName db-name
                                            :credentials (Credentials/fromGeneratedSecret "postgres")}))
(defn- InitializeCluster [stack vpc {:keys [name]}]
  (Cluster stack (str name "-cluster") {:vpc vpc}))

(defn- InitializeAlbFs [stack cluster repo rds {:keys [name]}]
  (ApplicationLoadBalancedFargateService
   stack
   (str name "-fargate")
   {:cluster cluster
    :desiredCount 1
    :taskImageOptions (TaskImageOptions repo rds)
    :memoryLimitMiB 2048
    :assignPublicIp true
    :publicLoadBalancer true}))

(defn- InitializeARecord [stack albfs {:keys [name subdomain]}]
  (ARecord stack (str name "-arecord") {:recordName (str subdomain "." domain)
                                        :zone (HostedZone/fromHostedZoneAttributes stack (str name "-zone") (HostedZoneAttributes {:hostedZoneId hosted-zone-id
                                                                                                                                   :zoneName domain}))
                                        :target (RecordTarget/fromAlias (LoadBalancerTarget (cdk/get albfs :loadBalancer)))}))

(defn PastaXianaBackendStack [app config]
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
