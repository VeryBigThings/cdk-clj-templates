## Quick Start

1. Add this inside `deps.edn` file.

``` clojure
{:paths   ["src"]
 :deps    {org.clojure/clojure {:mvn/version "1.11.1"}}
 :aliases {:dev {:extra-paths ["cdk"]
                 :extra-deps  {verybigthings/cdk-clj-templates {:git/url "https://github.com/verybigthings/cdk-clj-templates.git"
                                                                :sha     "<LATEST SHA HERE>"}}}}}
```

2. Create a CDK infrastructure file with the path `./cdk/cdk/entry.clj`.

``` clojure
(ns cdk.entry
  (:require [verybigthings.cdk :as cdk]
            [verybigthings.cdk-clj-templates.pasta-xiana.backend :refer [PastaXianaBackendStack]]))

(defn AppStack
  [scope id props]
  (let [stack (Stack scope id props)]
    (Bucket stack "bucket" {:versioned true})))

(cdk/defapp exampleApp
  [app]
  (PastaXianaBackendStack app {:name "pasta-dev"
                               :region "us-east-1"
                               :account "22222222222"
                               :repo-name "pasta"
                               :db-name "dbname"
                               :subdomain "pasta"}))
```

3. Create `cdk.json` in the root of your project with following:

```json
{"app":"clojure -A:dev -M cdk/cdk/entry.clj"}
```

4. Verify evertying works correctly

``` shell
cdk ls
# should return `pasta-dev`
```

## Contributing

Contributors are welcome to submit issues, bug reports, and feature requests. 

## License

cdk-clj-templates is distributed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

See [LICENSE](LICENSE) for more information.
