import {totp} from './encoding.js';

const profileAttributesHash = '08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0';

export const webLogin={url:'https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F',cookieUrl:'https://open.spotify.com/',cookieField:'cookie',requiredCookies:['sp_dc'],allowedHosts:['accounts.spotify.com','open.spotify.com','spotify.com','www.spotify.com','accounts.google.com','www.google.com','appleid.apple.com','account.apple.com','facebook.com','www.facebook.com','m.facebook.com']};
export const loginRequest={fields:[{id:'cookie',label:'Spotify session cookie',secret:true}],instructions:'Sign in to Spotify in the browser, or paste a cookie header containing sp_dc. Including sp_t enables personalization.'};
export const loginUi={request:webLogin,labels:{title:'Sign in to Spotify',hint:loginRequest.instructions,error:'Spotify could not verify this session. Sign in again or retry.',retry:'Retry',cancel:'Cancel',login:'Sign in',logout:'Sign out'}};
export function cookieInput(value) {
  if(typeof value!=='string'||value.length>65536||/[\r\n\x00]/.test(value))throw Error('INVALID_CREDENTIALS');
  value=value.trim().replace(/^cookie:\s*/i,'').replace(/^;+|;+$/g,'').trim();
  const values=new Map();
  if(!value.includes('=')) { if(!/^[A-Za-z0-9_+/=-]{16,4096}$/.test(value))throw Error('INVALID_CREDENTIALS');values.set('sp_dc',value); }
  else for(const part of value.split(';')) {
    const n=part.indexOf('=');if(n<1)continue;const key=part.slice(0,n).trim(),content=part.slice(n+1).trim();
    if(['sp_dc','sp_t','sp_key','sp_landing'].includes(key.toLowerCase())&&content&&/^[^;\s]+$/.test(content))values.set(key.toLowerCase(),content);
  }
  if(!values.get('sp_dc'))throw Error('INVALID_CREDENTIALS');
  return [...values].map(([k,v])=>k+'='+v).join('; ');
}
export class SpotifySession {
  constructor(host) { this.host=host;this.cookie=null;this.revision=0;this.tokenCache=null;this.pendingToken=null;this.desktopCache=null;this.pendingDesktop=null;this.nuance=null;this.hashes=new Map(Object.entries({decorateContextTracks:'383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7',home:'d62af2714f2623c923cc9eeca4b9545b4363abaa9188a9e94e2b63b823419a2c',fetchPlaylistMetadata:'a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4',getAlbumNameAndTracks:'8628ad33de3267d7bef516c76a746979a5f98891a2c9eaff3dfec828abdcd983',getArtistNameAndTracks:'0adaf1a1a8a94c7ed095639c4d9456d2b1cfac16ac511d5dd2b01b6dd89f748a'}));this.bundles=new Map(); }
  clear() { this.revision++;this.cookie=null;this.tokenCache=null;this.pendingToken=null;this.desktopCache=null;this.pendingDesktop=null; }
  snapshot(cookie=this.cookie) { if(!cookie)throw Error('LOGIN_REQUIRED');return {cookie,revision:this.revision}; }
  check(snapshot) { if(snapshot.revision!==this.revision)throw Error('ACCOUNT_CHANGED'); }
  async request(request) {
    const result=await this.host.http({timeoutMs:20000,maxBytes:2097152,...request});
    if(!result||!Number.isInteger(result.status))throw Error('INVALID_HTTP_RESPONSE');
    return result;
  }
  async publicJson(url) { const response=await this.request({url});if(response.status!==200)throw Error('SPOTIFY_BOOTSTRAP_HTTP_'+response.status);return JSON.parse(response.body); }
  async token(snapshot=this.snapshot(),force=false) {
    this.check(snapshot);
    if(!force&&this.tokenCache?.cookie===snapshot.cookie&&this.tokenCache.expires>Date.now())return this.tokenCache.value;
    if(!force&&this.pendingToken?.cookie===snapshot.cookie)return this.pendingToken.promise;
    const promise=(async()=>{
      if(!this.nuance) {
        const gist=await this.publicJson('https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef');
        const entries=JSON.parse(gist.files?.['nuances.json']?.content??'null');
        this.nuance=Array.isArray(entries)?entries.filter(x=>typeof x.s==='string'&&Number.isInteger(x.v)).sort((a,b)=>b.v-a.v)[0]:null;
        if(!this.nuance)throw Error('SPOTIFY_TOTP_UNAVAILABLE');
      }
      const time=await this.publicJson('https://open.spotify.com/api/server-time');
      if(!Number.isFinite(Number(time.serverTime)))throw Error('SPOTIFY_SERVER_TIME_UNAVAILABLE');
      const otp=totp(this.nuance.s,Number(time.serverTime));
      const dc=snapshot.cookie.split('; ').find(x=>x.startsWith('sp_dc='));
      const response=await this.request({url:'https://open.spotify.com/api/token?reason=transport&productType=web-player&totp='+otp+'&totpServer='+otp+'&totpVer='+this.nuance.v,
        headers:{Cookie:dc,Accept:'application/json',Referer:'https://open.spotify.com/'}});
      if(response.status!==200)throw Error('SPOTIFY_AUTH_HTTP_'+response.status);
      const token=JSON.parse(response.body);if(typeof token.accessToken!=='string'||!token.accessToken||token.isAnonymous===true)throw Error('SESSION_EXPIRED');
      this.check(snapshot);
      const expires=Number(token.accessTokenExpirationTimestampMs);
      if(!Number.isFinite(expires)||expires<=Date.now())throw Error('INVALID_TOKEN_EXPIRY');
      this.tokenCache={cookie:snapshot.cookie,value:token.accessToken,expires:expires-60000};return token.accessToken;
    })();
    this.pendingToken={cookie:snapshot.cookie,promise};
    try{return await promise;}finally{if(this.pendingToken?.promise===promise)this.pendingToken=null;}
  }
  async auth(url,{method='GET',body,headers={},desktop=false,...options}={},snapshot=this.snapshot(),retry=true) {
    let token;
    try{token=desktop?await this.desktopToken(snapshot):await this.token(snapshot);}catch(error){this.check(snapshot);if(desktop)throw error;desktop=true;token=await this.desktopToken(snapshot);}
    const response=await this.request({url,method,body,headers:{Accept:'application/json','App-Platform':'WebPlayer',Referer:'https://open.spotify.com/',Origin:'https://open.spotify.com',Cookie:snapshot.cookie,Authorization:'Bearer '+token,...headers},...options});
    this.check(snapshot);
    if(response.status===401&&retry){if(desktop)this.desktopCache=null;else await this.token(snapshot,true);return this.auth(url,{method,body,headers,desktop,...options},snapshot,false);}
    if(response.status===403&&retry&&!desktop)return this.auth(url,{method,body,headers,desktop:true,...options},snapshot,false);
    if(response.status<200||response.status>=300)throw Error('SPOTIFY_HTTP_'+response.status);
    return response;
  }
  async json(url,options={},snapshot=this.snapshot()) {const response=await this.auth(url,options,snapshot);return response.body?JSON.parse(response.body):{};}
  async rest(path,params={},snapshot=this.snapshot(),options={}) {
    if(!path.startsWith('/')||path.includes('://'))throw Error('INVALID_SPOTIFY_PATH');
    const query=Object.entries(params).filter(([,v])=>v!=null).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(String(v))).join('&');
    return this.json('https://api.spotify.com/v1'+path+(query?'?'+query:''),options,snapshot);
  }
  async verify(candidate) {
    const snapshot = {cookie: cookieInput(candidate), revision: ++this.revision};
    const nonBlank = value => typeof value === 'string' && value.trim() ? value : null;
    let accountName;
    try {
      const data = await this.graph('profileAttributes', {}, snapshot);
      const profile = data?.data?.me?.profile;
      accountName = nonBlank(profile?.name) ?? nonBlank(profile?.username);
    } catch (error) {
      this.check(snapshot);
      if (error.message === 'SPOTIFY_HTTP_429') throw error;
    }
    if (!accountName) {
      const user = await this.rest('/me', {}, snapshot);
      if (!nonBlank(user.id)) throw Error('ACCOUNT_VERIFICATION_FAILED');
      accountName = nonBlank(user.display_name) ?? user.id;
    }
    this.check(snapshot);
    this.cookie = snapshot.cookie;
    return {loggedIn: true, accountName};
  }
  async desktopToken(snapshot=this.snapshot()) {
    this.check(snapshot);if(this.desktopCache?.cookie===snapshot.cookie&&this.desktopCache.expires>Date.now())return this.desktopCache.value;
    if(this.pendingDesktop?.cookie===snapshot.cookie)return this.pendingDesktop.promise;
    const promise=(async()=>{
      const clientId='65b708073fc0480ea92a077233ca87bd',scope='playlist-read,playlist-read-collaborative,playlist-read-private,user-follow-read,user-library-modify,user-library-read,user-personalized,user-read-play-history,user-read-playback-position,user-read-playback-state,user-read-private,user-read-recently-played,user-top-read';
      const form=values=>Object.entries(values).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&');
      const authorization=await this.request({url:'https://accounts.spotify.com/oauth2/device/authorize',method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:form({client_id:clientId,scope})});
      if(authorization.status!==200)throw Error('SPOTIFY_DEVICE_AUTH_HTTP_'+authorization.status);
      const flow=JSON.parse(authorization.body);if(!flow.device_code||!flow.user_code||!/^https:\/\/accounts\.spotify\.com\//.test(flow.verification_uri_complete??''))throw Error('SPOTIFY_DEVICE_AUTH_UNAVAILABLE');
      const cookies=new Map(snapshot.cookie.split('; ').map(x=>{const n=x.indexOf('=');return [x.slice(0,n),x.slice(n+1)];}));
      const cookieHeader=()=>[...cookies].map(([k,v])=>k+'='+v).join('; ');
      let url=flow.verification_uri_complete,verification;
      for(let redirects=0;redirects<10;redirects++) {
        if(!/^https:\/\/(?:accounts\.spotify\.com|open\.spotify\.com|www\.spotify\.com|spotify\.com)\//.test(url))throw Error('INVALID_SPOTIFY_REDIRECT');
        verification=await this.request({url,headers:{Cookie:cookieHeader(),Referer:'https://open.spotify.com/'}});this.check(snapshot);
        for(const raw of verification.setCookies??[]){const pair=/^([!#$%&'*+.^_`|~0-9A-Za-z-]+)=([^;]*)/.exec(raw);if(pair)cookies.set(pair[1],pair[2]);}
        if(verification.status<300||verification.status>=400)break;
        const location=Object.entries(verification.headers??{}).find(([k])=>k.toLowerCase()==='location')?.[1];if(typeof location!=='string')throw Error('SPOTIFY_REDIRECT_UNAVAILABLE');
        const origin=/^https:\/\/[^/]+/.exec(url)?.[0];url=location.startsWith('//')?'https:'+location:location.startsWith('/')?origin+location:location.startsWith('https://')?location:url.slice(0,url.lastIndexOf('/')+1)+location;
      }
      if(verification?.status!==200)throw Error('SPOTIFY_DEVICE_VERIFICATION_HTTP_'+verification?.status);
      const flowContext=/(?:[?&])flow_ctx=([^&#]+)/.exec(verification.finalUrl??url)?.[1],match=/<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i.exec(verification.body);
      const csrf=match?JSON.parse(match[1]).props?.initialToken:null;if(!flowContext||typeof csrf!=='string'||!csrf)throw Error('SPOTIFY_DEVICE_VERIFICATION_UNAVAILABLE');
      const confirm=await this.request({url:'https://accounts.spotify.com/pair/api/resolve?flow_ctx='+encodeURIComponent(decodeURIComponent(flowContext).split(':')[0]+':'+Math.floor(Date.now()/1000)),method:'POST',headers:{Cookie:cookieHeader(),'Content-Type':'application/json','x-csrf-token':csrf,Referer:url,Origin:'https://accounts.spotify.com'},body:JSON.stringify({code:flow.user_code})});
      if(confirm.status!==200||JSON.parse(confirm.body).result!=='ok')throw Error('SPOTIFY_DEVICE_CONFIRMATION_FAILED');
      const exchange=await this.request({url:'https://accounts.spotify.com/api/token',method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:form({client_id:clientId,device_code:flow.device_code,grant_type:'urn:ietf:params:oauth:grant-type:device_code'})});
      if(exchange.status!==200)throw Error('SPOTIFY_DEVICE_TOKEN_HTTP_'+exchange.status);const token=JSON.parse(exchange.body);this.check(snapshot);
      if(typeof token.access_token!=='string'||!token.access_token||!Number.isFinite(Number(token.expires_in)))throw Error('SPOTIFY_DEVICE_TOKEN_UNAVAILABLE');
      this.desktopCache={cookie:snapshot.cookie,value:token.access_token,expires:Date.now()+Number(token.expires_in)*1000-60000};return token.access_token;
    })();this.pendingDesktop={cookie:snapshot.cookie,promise};
    try{return await promise;}finally{if(this.pendingDesktop?.promise===promise)this.pendingDesktop=null;}
  }
  async graph(operation,variables={},snapshot=this.snapshot(),refresh=false) {
    const operationHash=({fetchPlaylistContents:'fetchPlaylist',fetchPlaylistContentsWithGatedEntityRelations:'fetchPlaylist',removeFromLibrary:'addToLibrary'})[operation]??operation;
    if(refresh)this.hashes.delete(operationHash);
    let hash=this.hashes.get(operationHash);
    if (!hash && !refresh && operationHash === 'profileAttributes') hash = profileAttributesHash;
    if(!hash) { await this.discoverHashes(new Set([operationHash]));hash=this.hashes.get(operationHash); }
    if(!hash)throw Error('SPOTIFY_GRAPHQL_OPERATION_UNAVAILABLE');
    const data=await this.json('https://api-partner.spotify.com/pathfinder/v2/query',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({operationName:operation,variables,extensions:{persistedQuery:{version:1,sha256Hash:hash}}})},snapshot);
    if(data.errors?.length){if(!refresh&&data.errors.some(x=>/persisted|hash/i.test(x.message??'')))return this.graph(operation,variables,snapshot,true);throw Error('SPOTIFY_GRAPHQL_REJECTED');}
    return data;
  }
  async discoverHashes(wanted) {
    const response=await this.request({url:'https://open.spotify.com/',maxBytes:4194304});if(response.status!==200)throw Error('SPOTIFY_WEBPLAYER_UNAVAILABLE');
    const scripts=[...String(response.body).matchAll(/<script[^>]+src=["']([^"']+)["']/g)].map(m=>m[1]).map(p=>p.startsWith('//')?'https:'+p:p.startsWith('/')?'https://open.spotify.com'+p:p)
      .filter(p=>/^https:\/\/(?:open\.spotify\.com|[^/]*\.scdn\.co)\//.test(p)&&/\/(?:mobile-)?web-player\//.test(p)&&p.endsWith('.js'));
    const scan=body=>{for(const name of wanted){const escaped=name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');const patterns=[new RegExp(escaped+'[\\s\\S]{0,800}?sha256Hash\\\\?["\']:\\\\?["\']([a-f0-9]{64})'),new RegExp('["\']'+escaped+'["\'],["\'](?:query|mutation)["\'],["\']([a-f0-9]{64})["\']'),new RegExp('operationName:["\']'+escaped+'["\'][\\s\\S]{0,1000}?sha256Hash:["\']([a-f0-9]{64})["\']')];for(const regex of patterns){const found=regex.exec(body);if(found){this.hashes.set(name,found[1]);break;}}}};
    for(const script of scripts.slice(0,8)) {
      let body=this.bundles.get(script);if(!body){const r=await this.request({url:script,maxBytes:8388608});if(r.status!==200)continue;body=r.body;this.bundles.set(script,body);while(this.bundles.size>2)this.bundles.delete(this.bundles.keys().next().value);}scan(body);
      if([...wanted].every(x=>this.hashes.has(x)))return;
      const maps=[...body.matchAll(/\{(?:\d+:["'][^"']+["'],?)+\}/g)].map(m=>Object.fromEntries([...m[0].matchAll(/(\d+):["']([^"']+)["']/g)].map(x=>[x[1],x[2]])));
      const names=maps.find(m=>Object.values(m).filter(v=>!/^\w{8,64}$/.test(v)||/[.-]/.test(v)).length>Object.keys(m).length*0.4);
      const hashes=maps.find(m=>Object.values(m).filter(v=>/^[a-f0-9]{8,64}$/.test(v)).length>Object.keys(m).length*0.6);
      if(!names||!hashes)continue;
      const base=script.slice(0,script.lastIndexOf('/')+1);
      for(const id of Object.keys(names).filter(id=>hashes[id]).slice(0,300)) {
        const url=base+names[id]+'.'+hashes[id]+'.js';const r=await this.request({url,maxBytes:4194304});if(r.status!==200)continue;scan(r.body);
        if([...wanted].every(x=>this.hashes.has(x)))return;
      }
    }
  }
}
