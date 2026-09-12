{{/* Shared naming and the bits every workload repeats. */}}

{{- define "niem.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "niem.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "niem.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "niem.labels" -}}
app.kubernetes.io/name: {{ include "niem.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
  The image reference.

  A digest wins over a tag when both are set. An air-gapped install loads the image from a tarball
  and has no registry to resolve a tag against, so the digest is what makes the deployment reproduce.
*/}}
{{- define "niem.image" -}}
{{- if .Values.image.digest -}}
{{ .Values.image.repository }}@{{ .Values.image.digest }}
{{- else -}}
{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}
{{- end -}}
{{- end -}}

{{/*
  Credentials, always from Secrets and never from values.

  Anything put in values.yaml appears in `helm get values`, in CI output, and in whatever stores the
  release history. For a system holding criminal justice data that is not an acceptable place for an
  object store secret to live.
*/}}
{{- define "niem.credentialEnv" -}}
{{- with .Values.storage.silver }}
{{- if and .enabled .s3.existingSecret }}
- name: NIEM_S3_SECRET_ACCESS_KEY
  valueFrom:
    secretKeyRef:
      name: {{ .s3.existingSecret }}
      key: {{ .s3.secretKey }}
{{- end }}
{{- end }}
{{- with .Values.storage.graph }}
{{- if and .enabled .existingSecret }}
- name: NIEM_NEO4J_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .existingSecret }}
      key: {{ .secretKey }}
{{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}

{{/* Volumes shared by ingest and replay. */}}
{{- define "niem.volumes" -}}
- name: bronze
  persistentVolumeClaim:
    claimName: {{ .Values.storage.bronze.existingClaim | default (printf "%s-bronze" (include "niem.fullname" .)) }}
- name: drop
  persistentVolumeClaim:
    claimName: {{ .Values.drop.existingClaim | default (printf "%s-drop" (include "niem.fullname" .)) }}
{{- if .Values.storage.checkpoints.enabled }}
- name: checkpoints
  persistentVolumeClaim:
    claimName: {{ .Values.storage.checkpoints.existingClaim | default (printf "%s-checkpoints" (include "niem.fullname" .)) }}
{{- end }}
{{- if eq .Values.module.source "configMap" }}
- name: module
  configMap:
    name: {{ required "module.configMapName is required when module.source is configMap" .Values.module.configMapName }}
{{- else if eq .Values.module.source "pvc" }}
- name: module
  persistentVolumeClaim:
    claimName: {{ required "module.claimName is required when module.source is pvc" .Values.module.claimName }}
    readOnly: true
{{- end }}
{{/*
  readOnlyRootFilesystem is on, so anything the JVM or the platform writes needs somewhere to go.
  Temp is the only such place; every durable path is an explicit volume above.
*/}}
- name: tmp
  emptyDir: {}
{{- with .Values.extraVolumes }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}

{{- define "niem.volumeMounts" -}}
- name: bronze
  mountPath: /var/lib/niem/bronze
- name: drop
  mountPath: /srv/drop
{{- if .Values.storage.checkpoints.enabled }}
- name: checkpoints
  mountPath: /var/lib/niem/checkpoints
{{- end }}
{{- if ne .Values.module.source "none" }}
- name: module
  mountPath: {{ .Values.module.mountPath }}
  readOnly: true
{{- end }}
- name: tmp
  mountPath: /tmp
{{- with .Values.extraVolumeMounts }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}

{{/* The --silver-* and --s3-* arguments, or none when silver is not configured. */}}
{{- define "niem.silverArgs" -}}
{{- with .Values.storage.silver }}
{{- if .enabled }}
- --silver-catalog-uri={{ required "storage.silver.catalogUri is required when silver is enabled" .catalogUri }}
- --silver-warehouse={{ required "storage.silver.warehouse is required when silver is enabled" .warehouse }}
- --silver-catalog-name={{ .catalogName }}
{{- if .s3.endpoint }}
- --s3-endpoint={{ .s3.endpoint }}
{{- end }}
{{- if .s3.accessKeyId }}
- --s3-access-key-id={{ .s3.accessKeyId }}
{{- end }}
- --s3-region={{ .s3.region }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
  How the source is named on the command line: a definition artifact, or the drop shorthand.

  One helper rather than the two branches written out at each call site, for the reason ADR 0029
  gives for both CLI routes meeting before anything is landed -- two spellings of the same thing
  drift, and the drift shows up as a job that lands from somewhere nobody chose.
*/}}
{{- define "niem.sourceArgs" -}}
{{- if .Values.ingest.source }}
- --source={{ .Values.ingest.source }}
{{- else }}
- --drop=/srv/drop
- --pattern={{ .Values.ingest.filePattern }}
- --skip-header-lines={{ .Values.ingest.skipHeaderLines }}
{{- end }}
{{- if .Values.storage.checkpoints.enabled }}
- --checkpoints=/var/lib/niem/checkpoints
{{- end }}
{{- end -}}
