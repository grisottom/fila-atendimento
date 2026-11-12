{{/* URL pública (com porta) de um host: https://host:porta */}}
{{- define "fila.url" -}}
{{- printf "%s://%s:%s" .root.Values.exposure.scheme .host .root.Values.exposure.port -}}
{{- end -}}

{{/* Imagem de um componente construído pela pipeline */}}
{{- define "fila.image" -}}
{{- printf "%s/%s:%s" .root.Values.image.registry .name .root.Values.image.tag -}}
{{- end -}}

{{/* Labels comuns; recebe dict "root" e "name" */}}
{{- define "fila.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: fila-atendimento
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end -}}
