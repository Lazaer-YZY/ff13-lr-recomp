import struct, sys
d = open(sys.argv[1], 'rb').read()
assert d[:4] == b'XEX2', d[:4]
flags, pe_off, _, sec_off, nopt = struct.unpack_from('>IIIII', d, 4)
print(f"module_flags=0x{flags:08X} pe_data_offset=0x{pe_off:X} security_info_offset=0x{sec_off:X} opt_headers={nopt}")
names = {0x00010201:'image_base', 0x00010100:'entry_point', 0x000080FF:'original_pe_name', 0x00020200:'default_stack_size',
         0x000103FF:'import_libraries', 0x00040006:'execution_info', 0x00030000:'file_format_info', 0x000002FF:'resource_info',
         0x00000405:'system_flags', 0x00040310:'game_ratings', 0x000405FF:'lan_key', 0x00018002:'original_base_address',
         0x00020104:'tls_info', 0x00028002:'alternate_title_ids', 0x00040404:'xbox360_logo', 0x00040007:'title_workspace_size',
         0x00040008:'game_ratings2', 0x000200FF:'static_libraries', 0x00020001:'checksum_timestamp', 0x00018001:'bound_path',
         0x00040310:'ratings', 0x000183FF:'device_id', 0x000201FF:'enabled_for_callcap', 0x00020401:'enabled_for_fastcap'}
for i in range(nopt):
    key, val = struct.unpack_from('>II', d, 0x18 + 8*i)
    nm = names.get(key, f'key_{key:08X}')
    if key == 0x00040006:
        media_id, ver, base_ver, title_id, plat, exe_tbl, disc_no, disc_cnt, save_id = struct.unpack_from('>IIIIBBBBI', d, val)
        def v(x): return f"{x>>28}.{(x>>24)&0xF}.{(x>>8)&0xFFFF}.{x&0xFF}"
        print(f"  execution_info: media_id={media_id:08X} title_id={title_id:08X} version={v(ver)} base_version={v(base_ver)} platform={plat} disc={disc_no}/{disc_cnt} savegame_id={save_id:08X}")
    elif key == 0x00030000:
        size, enc, comp = struct.unpack_from('>IHH', d, val)
        print(f"  file_format_info: encryption={enc} ({'none' if enc==0 else 'encrypted'}) compression={comp} ({ {0:'none',1:'basic',2:'normal(lzx)',3:'delta'}.get(comp) })")
    elif key == 0x000080FF:
        ln = struct.unpack_from('>I', d, val)[0]
        print(f"  original_pe_name: {d[val+4:val+4+ln].rstrip(b'\0').decode(errors='replace')}")
    elif key == 0x00010201:
        print(f"  image_base: 0x{val:08X}")
    elif key == 0x00010100:
        print(f"  entry_point: 0x{val:08X}")
    elif key == 0x00020200:
        print(f"  default_stack_size: 0x{val:X}")
    elif key == 0x000200FF:
        ln = struct.unpack_from('>I', d, val)[0]
        n = (ln-4)//16
        libs = []
        for j in range(n):
            o = val+4+16*j
            nm2 = d[o:o+8].rstrip(b'\0').decode(errors='replace')
            a,b,c,e = struct.unpack_from('>HHHH', d, o+8)
            libs.append(f"{nm2} {a}.{b}.{c}.{e&0x7FFF}")
        print(f"  static_libraries: {'; '.join(libs)}")
    elif key == 0x000103FF:
        ln, strsz, cnt = struct.unpack_from('>III', d, val)
        s = d[val+12:val+12+strsz].split(b'\0')
        print(f"  import_libraries: count={cnt} names={[x.decode() for x in s if x]}")
    else:
        print(f"  {nm}: 0x{val:08X}")
# security info
img_size, = struct.unpack_from('>I', d, sec_off+4)
load_addr, = struct.unpack_from('>I', d, sec_off+0x10C)  # approximate: xex2_security_info.load_address
print(f"security: image_size=0x{img_size:X} (parsed field at +4) load_address_field=0x{load_addr:08X}")
