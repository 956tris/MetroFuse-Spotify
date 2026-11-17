/* MetroFuse contributors, GPL-3.0. No Node/browser dependencies: runs in QuickJS. */
export function utf8(text) {
  const out=[];
  for (const ch of text) {
    const n=ch.codePointAt(0);
    if(n<128)out.push(n);else if(n<2048)out.push(192|(n>>6),128|(n&63));
    else if(n<65536)out.push(224|(n>>12),128|((n>>6)&63),128|(n&63));
    else out.push(240|(n>>18),128|((n>>12)&63),128|((n>>6)&63),128|(n&63));
  }
  return out;
}
export function text(bytes) {
  let out='';
  for(let i=0;i<bytes.length;) {
    const a=bytes[i++];let n=a;
    if(a>=240)n=((a&7)<<18)|((bytes[i++]&63)<<12)|((bytes[i++]&63)<<6)|(bytes[i++]&63);
    else if(a>=224)n=((a&15)<<12)|((bytes[i++]&63)<<6)|(bytes[i++]&63);
    else if(a>=192)n=((a&31)<<6)|(bytes[i++]&63);
    out+=String.fromCodePoint(n);
  }
  return out;
}
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function base64(bytes) {
  let out='';for(let i=0;i<bytes.length;i+=3) {
    const a=bytes[i],b=bytes[i+1],c=bytes[i+2],n=(a<<16)|((b??0)<<8)|(c??0);
    out+=alphabet[(n>>18)&63]+alphabet[(n>>12)&63]+(b===undefined?'=':alphabet[(n>>6)&63])+(c===undefined?'=':alphabet[n&63]);
  }return out;
}
export function unbase64(value) {
  if(typeof value!=='string'||!/^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value))throw Error('INVALID_BASE64');
  const out=[];for(let i=0;i<value.length;i+=4) {
    const a=alphabet.indexOf(value[i]),b=alphabet.indexOf(value[i+1]),c=alphabet.indexOf(value[i+2]),d=alphabet.indexOf(value[i+3]);
    out.push((a<<2)|(b>>4));if(value[i+2]!=='=')out.push(((b&15)<<4)|(c>>2));if(value[i+3]!=='=')out.push(((c&3)<<6)|d);
  }return out;
}
const rol=(x,n)=>(x<<n)|(x>>>(32-n));
export function sha1(input) {
  const bytes=[...input],bits=bytes.length*8;bytes.push(128);while(bytes.length%64!==56)bytes.push(0);
  for(let n=7;n>=0;n--)bytes.push(Math.floor(bits/2**(n*8))&255);
  let h=[0x67452301,0xefcdab89,0x98badcfe,0x10325476,0xc3d2e1f0];
  for(let off=0;off<bytes.length;off+=64) {
    const w=[];for(let i=0;i<16;i++)w[i]=(bytes[off+i*4]<<24)|(bytes[off+i*4+1]<<16)|(bytes[off+i*4+2]<<8)|bytes[off+i*4+3];
    for(let i=16;i<80;i++)w[i]=rol(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
    let [a,b,c,d,e]=h;
    for(let i=0;i<80;i++) {
      const f=i<20?(b&c)|(~b&d):i<40?b^c^d:i<60?(b&c)|(b&d)|(c&d):b^c^d;
      const k=i<20?0x5a827999:i<40?0x6ed9eba1:i<60?0x8f1bbcdc:0xca62c1d6;
      const t=(rol(a,5)+f+e+k+w[i])|0;e=d;d=c;c=rol(b,30);b=a;a=t;
    }h=h.map((n,i)=>(n+[a,b,c,d,e][i])|0);
  }
  return h.flatMap(n=>[n>>>24,(n>>>16)&255,(n>>>8)&255,n&255]);
}
export function hmacSha1(key,value) {
  if(key.length>64)key=sha1(key);const padded=[...key];while(padded.length<64)padded.push(0);
  return sha1([...padded.map(n=>n^92),...sha1([...padded.map(n=>n^54),...value])]);
}
export function totp(secret,seconds) {
  const chars='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';let buf=0,bits=0;const key=[];
  for(const ch of String(secret).toUpperCase().replace(/[=\s]/g,'')) {
    const v=chars.indexOf(ch);if(v<0)throw Error('INVALID_TOTP_SECRET');buf=(buf<<5)|v;bits+=5;if(bits>=8){bits-=8;key.push((buf>>>bits)&255);}
  }
  const counter=Math.floor(seconds/30),data=[];for(let n=7;n>=0;n--)data.push(Math.floor(counter/2**(n*8))&255);
  const hash=hmacSha1(key,data),off=hash[19]&15;
  const binary=((hash[off]&127)<<24)|(hash[off+1]<<16)|(hash[off+2]<<8)|hash[off+3];
  return String(binary%1000000).padStart(6,'0');
}
export function varint(value) { const out=[];while(value>=128){out.push((value%128)|128);value=Math.floor(value/128);}out.push(value);return out; }
export function protoBytes(field,value) { return [...varint(field*8+2),...varint(value.length),...value]; }
export function protoString(field,value) { return protoBytes(field,utf8(value)); }
export function protobuf(bytes) {
  let offset=0;const fields=new Map();
  const read=()=>{let value=0,scale=1;for(let i=0;i<10;i++){if(offset>=bytes.length)throw Error('INVALID_PROTOBUF');const n=bytes[offset++];value+=(n&127)*scale;if(!(n&128))return value;scale*=128;}throw Error('INVALID_PROTOBUF');};
  while(offset<bytes.length) {
    const tag=read(),field=Math.floor(tag/8),wire=tag%8;let value;
    if(!field)throw Error('INVALID_PROTOBUF');
    if(wire===0)value=read();else if(wire===2){const length=read();if(!Number.isSafeInteger(length)||length<0||offset+length>bytes.length)throw Error('INVALID_PROTOBUF');value=bytes.slice(offset,offset+length);offset+=length;}
    else if(wire===1||wire===5){const length=wire===1?8:4;if(offset+length>bytes.length)throw Error('INVALID_PROTOBUF');value=bytes.slice(offset,offset+length);offset+=length;}else throw Error('INVALID_PROTOBUF');
    if(!fields.has(field))fields.set(field,[]);fields.get(field).push(value);
  }return fields;
}
