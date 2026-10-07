"""Read PE export names from disk without loading or executing the binary."""
import mmap
import struct
from pathlib import Path

def exports(path):
    with Path(path).open('rb') as source, mmap.mmap(source.fileno(),0,access=mmap.ACCESS_READ) as data:
        def unpack(fmt,at): return struct.unpack_from(fmt,data,at)
        if data[:2]!=b'MZ': raise ValueError('Not PE')
        pe=unpack('<I',0x3c)[0]
        if data[pe:pe+4]!=b'PE\0\0': raise ValueError('Invalid PE signature')
        count=unpack('<H',pe+6)[0];optional=pe+24;optional_bytes=unpack('<H',pe+20)[0]
        magic=unpack('<H',optional)[0]
        if magic not in (0x10b,0x20b): raise ValueError('Unknown optional header')
        directory=optional+(112 if magic==0x20b else 96)
        export_rva,export_size=unpack('<II',directory)
        if not export_rva: return []
        sections=[]
        for index in range(count):
            at=optional+optional_bytes+40*index
            virtual_size,rva,raw_size,raw_at=unpack('<IIII',at+8)
            sections.append((rva,max(virtual_size,raw_size),raw_at,raw_size))
        def offset(rva):
            for base,size,raw,raw_size in sections:
                if base<=rva<base+size and rva-base<raw_size: return raw+rva-base
            raise ValueError('RVA has no file data')
        at=offset(export_rva)
        names_count=unpack('<I',at+24)[0];names=offset(unpack('<I',at+32)[0])
        if names_count>1000000: raise ValueError('Unreasonable export count')
        result=[]
        for index in range(names_count):
            start=offset(unpack('<I',names+4*index)[0]);end=data.find(b'\0',start,min(len(data),start+4096))
            if end<0: raise ValueError('Unterminated export')
            result.append(data[start:end].decode('ascii',errors='replace'))
        return result

if __name__=='__main__':
    import sys,json
    names=exports(sys.argv[1]);print(json.dumps({'export_count':len(names),'il2cpp_exports':[n for n in names if n.startswith('il2cpp_')]},indent=2))
