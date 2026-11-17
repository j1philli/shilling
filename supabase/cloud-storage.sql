-- Fresh hosted storage setup. The worker uses a server-only Storage credential.
INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
VALUES ('cloud-peer','cloud-peer',false,117440512,ARRAY['application/octet-stream']);
-- Restrictive policies also protect this bucket from any broader existing client policies.
CREATE POLICY cloud_peer_no_client_objects ON storage.objects AS RESTRICTIVE TO anon,authenticated
USING (bucket_id <> 'cloud-peer') WITH CHECK (bucket_id <> 'cloud-peer');
CREATE POLICY cloud_peer_no_client_bucket ON storage.buckets AS RESTRICTIVE TO anon,authenticated
USING (id <> 'cloud-peer') WITH CHECK (id <> 'cloud-peer');
