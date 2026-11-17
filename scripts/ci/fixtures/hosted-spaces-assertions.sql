\set ON_ERROR_STOP on
begin;
insert into auth.users values
 ('00000000-0000-0000-0000-000000000001','owner@example.test',now()),
 ('00000000-0000-0000-0000-000000000002','member@example.test',now()),
 ('00000000-0000-0000-0000-000000000003','outsider@example.test',now());
insert into public.user_profiles(user_id,household_id) values
 ('00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001'),
 ('00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000002'),
 ('00000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000003');
do $$
declare
 a uuid := '00000000-0000-0000-0000-000000000001';
 b uuid := '00000000-0000-0000-0000-000000000002';
 c uuid := '00000000-0000-0000-0000-000000000003';
 home uuid := '10000000-0000-0000-0000-000000000001';
 invite_id uuid;
 data jsonb;
begin
 if has_function_privilege('authenticated','public.manage_hosted_space(uuid,text,uuid,text,text,text,text,uuid,uuid,text,integer)','execute') then raise exception 'RPC exposed to client'; end if;
 begin
  perform public.manage_hosted_space(a,'create',p_name=>'Extra',p_kind=>'home',p_membership_limit=>1);
  raise exception 'Quota bypass';
 exception when check_violation then null; end;
 perform public.manage_hosted_space(a,'invite',home,p_email=>'member@example.test',p_role=>'member',p_token_hash=>repeat('a',64));
 begin
  perform public.manage_hosted_space(c,'accept',p_token_hash=>repeat('a',64));
  raise exception 'Invitation email bypass';
 exception when insufficient_privilege then null; end;
 begin
  perform public.manage_hosted_space(b,'accept',p_token_hash=>repeat('a',64));
  raise exception 'Accept quota bypass';
 exception when check_violation then null; end;
 perform public.manage_hosted_space(b,'leave','10000000-0000-0000-0000-000000000002');
 data := public.manage_hosted_space(b,'accept',p_token_hash=>repeat('a',64));
 if data->>'activeSpaceId' <> home::text then raise exception 'Accept did not select space'; end if;
 begin
  perform public.manage_hosted_space(b,'invite',home,p_email=>'outsider@example.test',p_role=>'member',p_token_hash=>repeat('b',64));
  raise exception 'Member management bypass';
 exception when insufficient_privilege then null; end;
 begin
  perform public.manage_hosted_space(a,'leave',home);
  raise exception 'Last owner bypass';
 exception when check_violation then null; end;
 perform public.manage_hosted_space(a,'role',home,p_role=>'admin',p_target=>b);
 begin
  perform public.manage_hosted_space(b,'remove',home,p_target=>a);
  raise exception 'Admin removed owner';
 exception when insufficient_privilege then null; end;
 perform public.manage_hosted_space(a,'invite',home,p_email=>'outsider@example.test',p_role=>'member',p_token_hash=>repeat('b',64));
 select id into invite_id from public.hosted_space_invitations where token_hash=repeat('b',64);
 perform public.manage_hosted_space(b,'revoke_invitation',home,p_invitation=>invite_id);
 begin
  perform public.manage_hosted_space(c,'accept',p_token_hash=>repeat('b',64));
  raise exception 'Revoked invitation accepted';
 exception when invalid_parameter_value then null; end;
 perform public.manage_hosted_space(a,'role',home,p_role=>'owner',p_target=>b);
 perform public.manage_hosted_space(a,'leave',home);
 if exists(select 1 from public.hosted_space_memberships where user_id=a and space_id=home) then raise exception 'Leave failed'; end if;
 if public.register_hosted_device(home,'removed-member-device',null,a) then raise exception 'Removed member registered a device'; end if;
 data := public.manage_hosted_space(c,'list',home);
 if data->>'activeSpaceId' is not null or jsonb_array_length(data->'members')<>0 then raise exception 'Outsider membership leak'; end if;
 data := public.manage_hosted_space(c,'create',p_name=>'Gold business',p_kind=>'business',p_membership_limit=>null);
 if jsonb_array_length(data->'spaces')<>2 then raise exception 'Gold create failed'; end if;
 data := public.manage_hosted_space(c,'list',p_membership_limit=>1);
 if not (data->>'requiresSpaceSelection')::boolean or data->>'activeSpaceId' is not null then raise exception 'Downgrade silently selected a space'; end if;
 data := public.manage_hosted_space(c,'select','10000000-0000-0000-0000-000000000003',p_membership_limit=>1);
 if (data->>'requiresSpaceSelection')::boolean or data->>'activeSpaceId'<>'10000000-0000-0000-0000-000000000003' then raise exception 'Downgrade selection failed'; end if;
 perform public.manage_hosted_space(c,'list',p_membership_limit=>null);
 data := public.manage_hosted_space(c,'list',p_membership_limit=>1);
 if not (data->>'requiresSpaceSelection')::boolean then raise exception 'Second downgrade reused an old selection'; end if;
end $$;
rollback;
