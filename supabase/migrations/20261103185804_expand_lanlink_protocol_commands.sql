alter table public.lanlink_commands
  drop constraint if exists lanlink_commands_kind_check;

alter table public.lanlink_commands
  add constraint lanlink_commands_kind_check
  check (kind = any (array[
    'discover'::text,
    'probe'::text,
    'http'::text,
    'wol'::text,
    'adb'::text,
    'ssdp'::text,
    'mdns'::text,
    'onvif'::text,
    'rtsp'::text
  ]));
