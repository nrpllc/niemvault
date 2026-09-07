#!/usr/bin/env bash
#
# Builds an air-gapped installation package (spec §6).
#
# §6 makes the air-gapped path mandatory rather than a fallback: many criminal justice environments
# will not permit outbound connectivity at all. So the package has to contain everything the install
# needs, and the install must not resolve anything at run time -- not an image tag, not a chart
# dependency, not a NIEM schema.
#
# What comes out is a directory an agency can carry in on media:
#
#   niem-platform-<version>/
#     image.tar          the platform image, loadable with `docker load` / `ctr images import`
#     image.digest       the digest to pin values.yaml to, so a tag is never resolved
#     chart/             the Helm chart
#     modules/           domain modules, as content (§7)
#     SHA256SUMS         checksums for everything above
#     INSTALL.md         what to do with it
#
# Deliberately not included: base images, a container runtime, or a Kubernetes distribution. Those
# are the environment, not the product, and pretending to ship them would hide the real prerequisites.

set -euo pipefail

VERSION="${1:-$(grep '^platformVersion=' gradle.properties | cut -d= -f2)}"
OUT="${2:-dist/niem-platform-${VERSION}}"
IMAGE="niem-platform:${VERSION}"

echo "Building ${IMAGE}"
docker build -f deploy/Dockerfile -t "${IMAGE}" .

mkdir -p "${OUT}/chart" "${OUT}/modules"

echo "Exporting image"
docker save "${IMAGE}" -o "${OUT}/image.tar"

# The digest is what values.yaml pins. A tag means the install has to ask a registry what it points
# at, which is exactly what an air-gapped environment cannot do.
docker image inspect "${IMAGE}" --format '{{index .RepoDigests 0}}' > "${OUT}/image.digest" 2>/dev/null \
  || docker image inspect "${IMAGE}" --format '{{.Id}}' > "${OUT}/image.digest"

echo "Copying chart and modules"
cp -r deploy/helm/niem-platform/. "${OUT}/chart/"
for module in modules/*/src/main/resources; do
  name="$(echo "${module}" | cut -d/ -f2)"
  mkdir -p "${OUT}/modules/${name}"
  cp -r "${module}/." "${OUT}/modules/${name}/"
done

cat > "${OUT}/INSTALL.md" <<'DOC'
# Installing without a network

1. Load the image on every node that will run it, or into your registry mirror:

       docker load -i image.tar          # or: ctr -n k8s.io images import image.tar

2. Create the module as a ConfigMap. Modules are content on their own release cycle (§7), which is
   why they are here as files rather than baked into the image:

       kubectl create configmap le-module --from-file=modules/law-enforcement

3. Create the object store credential. It is never put in values.yaml -- anything there appears in
   `helm get values`, in CI output, and in the release history:

       kubectl create secret generic niem-s3 --from-literal=NIEM_S3_SECRET_ACCESS_KEY=...

4. Install, pinning the digest from image.digest rather than a tag. There is no registry here to
   resolve a tag against:

       helm install le ./chart \
         --set image.digest="$(cat image.digest | cut -d@ -f2)" \
         --set module.configMapName=le-module \
         --set storage.silver.enabled=true \
         --set storage.silver.catalogUri=... \
         --set storage.silver.warehouse=... \
         --set storage.silver.s3.existingSecret=niem-s3

5. Check the content before the first scheduled run. This validates the mappings and contracts
   against each other and against the canonical model:

       kubectl run niem-validate --rm -it --restart=Never \
         --image=<digest> -- validate --module /srv/modules/active

The authoring surface is off by default and has no Service. If you enable it, reach it with
`kubectl port-forward` -- see ADR 0024 for why that is the only route.
DOC

echo "Checksumming"
( cd "${OUT}" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )

echo
echo "Package: ${OUT}"
du -sh "${OUT}" 2>/dev/null || true
