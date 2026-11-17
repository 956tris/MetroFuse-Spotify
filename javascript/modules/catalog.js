import {base64,unbase64,protobuf,protoBytes,protoString,text} from './encoding.js';

const arr=value=>Array.isArray(value)?value:Array.isArray(value?.items)?value.items:[];
export function unwrap(value) { for(let i=0;i<5&&value&&typeof value==='object';i++){const next=value.itemV2?.data??value.item?.data??value.item??value.data??(value.track?.data?{...value.track.data,uri:value.track.data.uri??value.track._uri}:value.track);if(!next||next===value)break;value=next;}return value; }
export function entityId(value,type='track') {
  if(typeof value!=='string')return null;
  const uri=new RegExp('^spotify:'+type+':([A-Za-z0-9]{22})$').exec(value),url=new RegExp('^https://open\\.spotify\\.com/(?:intl-[a-z]+/)?'+type+'/([A-Za-z0-9]{22})(?:[/?#]|$)').exec(value);
  return uri?.[1]??url?.[1]??(/^[A-Za-z0-9]{22}$/.test(value)?value:null);
}
export function identityId(identity,type='track') {
  return entityId(identity?.identifiers?.spotify,type)??entityId(identity?.identifiers?.['spotify.'+type],type)??entityId(identity?.canonicalId,type);
}
const name=x=>x?.profile?.name??x?.name??x?.title;
const picture=x=>[...arr(x?.images),...arr(x?.images).flatMap(i=>arr(i?.sources)),...arr(x?.coverArt?.sources),...arr(x?.visuals?.avatarImage?.sources),...arr(x?.visuals?.headerImage?.sources)]
  .filter(i=>typeof i?.url==='string').sort((a,b)=>(b.width??0)-(a.width??0))[0]?.url??'';
export function track(value) {
  value=unwrap(value);if(!value||value.is_local)return null;
  const id=entityId(value.uri??value.id),title=name(value);if(!id||typeof title!=='string'||!title.trim())return null;
  const artists=arr(value.artists).map(x=>unwrap(x)).map(x=>({id:entityId(x?.uri??x?.id,'artist'),name:name(x)})).filter(x=>typeof x.name==='string'&&x.name.trim());
  const album=unwrap(value.album??value.albumOfTrack),durationMs=Number(value.duration_ms??value.duration?.totalMilliseconds??value.duration?.milliseconds);
  const extras={provider:'spotify','spotify.uri':'spotify:track:'+id,explicit:String(value.explicit??value.contentRating?.label==='EXPLICIT'),
    'spotify.metadata':JSON.stringify(value)};
  const isrc=value.external_ids?.isrc??value.isrc;if(isrc)extras.isrc=isrc;
  const albumId=entityId(album?.uri??album?.id,'album');if(albumId)extras.albumId='spotify:album:'+albumId;
  if(artists.some(x=>x.id))extras.artistIds=artists.filter(x=>x.id).map(x=>'spotify:artist:'+x.id).join(',');
  if(value.album?.release_date)extras.releaseDate=value.album.release_date;
  return {id:'spotify:track:'+id,title,artist:artists.map(x=>x.name).join(', '),album:name(album)??'',duration:Number.isFinite(durationMs)&&durationMs>0?Math.floor(durationMs):0,artwork:picture(album)||picture(value),extras};
}
export function album(value,tracks=[]) {
  value=unwrap(value);const id=entityId(value?.uri??value?.id,'album'),title=name(value);if(!id||typeof title!=='string')return null;
  return {id:'spotify:album:'+id,title,artist:arr(value.artists).map(x=>name(unwrap(x))).filter(Boolean).join(', '),artwork:picture(value),tracks,
    metadata:{releaseDate:value.release_date??value.date?.isoString,totalTracks:value.total_tracks??value.tracks?.totalCount,explicit:value.contentRating?.label==='EXPLICIT',copyrights:value.copyrights??[],label:value.label,spotify:value}};
}
export function artist(value,tracks=[],albums=[]) {
  value=unwrap(value);const id=entityId(value?.uri??value?.id,'artist'),title=name(value);if(!id||typeof title!=='string')return null;
  return {id:'spotify:artist:'+id,name:title,artwork:picture(value),tracks,albums,metadata:{biography:value.profile?.biography?.text??value.biography,monthlyListeners:value.stats?.monthlyListeners,
    followers:value.stats?.followers??value.followers?.total,verified:value.profile?.verified??value.verified,spotify:value}};
}
export function playlist(value,tracks=[]) {
  value=unwrap(value);const raw=value?.uri??value?.id,id=entityId(raw,'playlist'),title=name(value);if(!id||typeof title!=='string')return null;
  return {id:'spotify:playlist:'+id,title,author:value.owner?.display_name??value.owner?.name??value.ownerV2?.data?.name??'',artwork:picture(value),tracks,
    metadata:{description:value.description??'',trackCount:value.tracks?.total??value.content?.totalCount,owner:value.owner??value.ownerV2?.data,editable:value.canEdit??false,spotify:value}};
}
export function homeItem(value) {
  value=unwrap(value);const uri=value?.uri??'',type=value?.__typename??value?.type??uri.split(':')[1];
  if(/track/i.test(type)){const v=track(value);return v?{track:v}:null;}
  if(/album|prerelease/i.test(type)){const v=album(value);return v?{album:v}:null;}
  if(/artist/i.test(type)){const v=artist(value);return v?{artist:v}:null;}
  if(/playlist/i.test(type)){const v=playlist(value);return v?{playlist:v}:null;}
  return null;
}
export function searchResult(root,limit=30) {
  const data=root.data?.searchV2??root.data?.search??root;
  const entries=keys=>keys.flatMap(key=>arr(data[key]));
  return {tracks:entries(['tracksV2','tracks']).map(track).filter(Boolean).slice(0,limit),
    albums:entries(['albumsV2','albums']).map(value=>album(value)).filter(Boolean).slice(0,limit),
    artists:entries(['artists']).map(value=>artist(value)).filter(Boolean).slice(0,limit),
    playlists:entries(['playlists']).map(value=>playlist(value)).filter(Boolean).slice(0,limit)};
}
export function homeShelves(root) {
  const found=[];let visited=0;
  const walk=(value,depth=0)=>{
    if(!value||depth>12||++visited>30000)return;
    if(Array.isArray(value)){for(const child of value)walk(child,depth+1);return;}
    if(typeof value!=='object')return;
    const data=value.data??value,title=value.title?.text??value.title??data.title?.text??data.title??data.header?.title?.text;
    const candidates=arr(value.sectionItems??value.items??value.content?.items??data.sectionItems??data.items??value.sectionContainer?.items);
    if(typeof title==='string'&&candidates.length){const items=candidates.map(homeItem).filter(Boolean);if(items.length)found.push({title,items});}
    for(const child of Object.values(value))walk(child,depth+1);
  };walk(root);
  const keys=new Set();return found.filter(s=>{const key=s.title+'|'+s.items.map(i=>(i.track??i.album??i.artist??i.playlist).id).join('|');if(keys.has(key))return false;keys.add(key);return true;});
}
function normal(value) {return String(value??'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
export function matchTrack(candidates,expectation) {
  const expectedIsrc=String(expectation.isrc??'').replace(/[^a-z0-9]/gi,'').toUpperCase();
  const title=normal(expectation.title),artistName=normal(expectation.artist),albumName=normal(expectation.album);let winner=null,best=0;
  for(const candidate of candidates) {
    const gotIsrc=String(candidate.extras?.isrc??'').replace(/[^a-z0-9]/gi,'').toUpperCase();
    if(expectedIsrc&&gotIsrc===expectedIsrc)return candidate;
    const gotTitle=normal(candidate.title),gotArtist=normal(candidate.artist),gotAlbum=normal(candidate.album);
    if(!title||!artistName||!gotArtist)continue;
    const titleScore=gotTitle===title?40:gotTitle.includes(title)||title.includes(gotTitle)?24:0;
    const artistScore=gotArtist===artistName?35:gotArtist.includes(artistName)||artistName.includes(gotArtist)?25:0;
    const difference=expectation.duration&&candidate.duration?Math.abs(expectation.duration-candidate.duration):0;
    if(!titleScore||!artistScore||difference>12000)continue;
    const score=titleScore+artistScore+(albumName&&gotAlbum===albumName?10:0)-(difference>4000?10:0);
    if(score>best){best=score;winner=candidate;}
  }return best>=59?winner:null;
}
export function usableCanvas(url) {return typeof url==='string'&&/^https:\/\/(?:canvaz\.scdn\.co|[^/]*\.scdn\.co|[^/]*\.spotifycdn\.com)\//i.test(url)&&!/(?:widevine|license|\.m3u8|\.mpd|manifest|drm)/i.test(url);}
export function parseCanvas(bytes,uri) {
  try {for(const message of protobuf(bytes).get(1)??[]){const fields=protobuf(message),gotUri=fields.get(5)?.[0];if(gotUri&&text(gotUri)!==uri)continue;const url=fields.get(2)?.[0];if(url&&usableCanvas(text(url)))return text(url);}}catch{}return null;
}
export function lyrics(root) {
  const value=root.lyrics??root,lines=arr(value.lines).filter(l=>typeof l.words==='string').map(l=>({text:l.words,timeMs:value.syncType==='LINE_SYNCED'?Number(l.startTimeMs):undefined}));
  return lines.length?{lines:lines.filter(l=>l.timeMs===undefined||Number.isFinite(l.timeMs)),source:'Spotify',language:value.language}:null;
}
export class SpotifyCatalog {
  constructor(session) {this.session=session;this.canvasCache=new Map();}
  async search(query,limit=30) {
    limit=Math.max(1,Math.min(Number(limit)||30,50));if(!String(query).trim())return {tracks:[],albums:[],artists:[],playlists:[]};
    const snapshot=this.session.snapshot();
    try{return searchResult(await this.session.graph('searchDesktop',{searchTerm:query,offset:0,limit,numberOfTopResults:5,includeAudiobooks:true,includePreReleases:true,includeLocalConcertsField:false,includeArtistHasConcertsField:false},snapshot),limit);}
    catch(error){this.session.check(snapshot);return searchResult(await this.session.rest('/search',{q:query,type:'track,album,artist,playlist',limit,market:'from_token'},snapshot),limit);}
  }
  async allPages(path,snapshot,limit=10000) {
    const out=[];let next=path,pages=0;
    while(next&&out.length<limit&&pages++<200) {
      const page=next.startsWith('/')?await this.session.rest(next,{limit:50},snapshot):await this.session.json(next,{},snapshot);
      out.push(...arr(page.items));next=page.next;
      if(next&&!/^https:\/\/api\.spotify\.com\/v1\//.test(next))throw Error('INVALID_CONTINUATION');
    }return out.slice(0,limit);
  }
  async hydrate(items,snapshot,albumValue) {
    const values=items.map(unwrap),missing=[...new Set(values.filter(v=>!track(v)||!arr(v?.artists).length).map(v=>entityId(v?.uri??v?.id)).filter(Boolean))],loaded=new Map();
    for(let offset=0;offset<missing.length;offset+=50){const ids=missing.slice(offset,offset+50);let entries;try{const root=await this.session.graph('decorateContextTracks',{uris:ids.map(id=>'spotify:track:'+id)},snapshot);entries=arr(root.data?.tracks).map(unwrap);if(!entries.length)throw Error('SPOTIFY_TRACK_METADATA_UNAVAILABLE');}catch(error){this.session.check(snapshot);entries=arr((await this.session.rest('/tracks',{ids:ids.join(','),market:'from_token'},snapshot)).tracks);}for(const value of entries){const id=entityId(value?.id??value?.uri);if(id)loaded.set(id,value);}}
    return values.map(v=>{const full=loaded.get(entityId(v?.uri??v?.id))??v;return track(albumValue?{...full,albumOfTrack:albumValue,album:full?.album??albumValue}:full);}).filter(Boolean);
  }
  async album(id) {
    id=entityId(id,'album');if(!id)return null;const snapshot=this.session.snapshot();
    try {
      const root=await this.session.graph('getAlbumNameAndTracks',{uri:'spotify:album:'+id,offset:0,limit:50},snapshot);
      const value=root.data?.albumUnion??root.data?.album;if(value){const raw=arr(value.tracksV2??value.tracks),tracks=await this.hydrate(raw,snapshot,value),total=Number(value.tracksV2?.totalCount??raw.length);
        for(let offset=raw.length;offset<total&&offset<10000;offset+=50){const page=await this.session.graph('getAlbumNameAndTracks',{uri:'spotify:album:'+id,offset,limit:50},snapshot),extra=arr((page.data?.albumUnion??page.data?.album)?.tracksV2);if(!extra.length)break;tracks.push(...await this.hydrate(extra,snapshot,value));}return album(value,tracks);}
    }catch(error){this.session.check(snapshot);}
    const value=await this.session.rest('/albums/'+id,{market:'from_token'},snapshot),items=arr(value.tracks?.items);
    let next=value.tracks?.next,pages=0;while(next&&items.length<10000&&pages++<200){if(!/^https:\/\/api\.spotify\.com\/v1\//.test(next))throw Error('INVALID_CONTINUATION');const page=await this.session.json(next,{},snapshot);items.push(...arr(page.items));next=page.next;}
    return album(value,items.map(v=>track({...v,album:value})).filter(Boolean));
  }
  async playlist(id) {
    if(['spotify:collection:tracks','collection:tracks','tracks'].includes(id)) {
      const snapshot=this.session.snapshot(),items=[];let continuation;for(let page=0;page<200;page++){const result=await this.library({likedSongsOnly:true,continuation});this.session.check(snapshot);items.push(...result.items.map(v=>v.track).filter(Boolean));continuation=result.continuation;if(!continuation)break;}
      return {id:'spotify:collection:tracks',title:'Liked Songs',author:'Spotify',artwork:items[0]?.artwork??'',tracks:items.map(t=>({...t,extras:{...t.extras,likeStatus:'LIKE'}}))};
    }
    id=entityId(id,'playlist');if(!id)return null;const snapshot=this.session.snapshot();
    try {
      const root=await this.session.graph('fetchPlaylistMetadata',{uri:'spotify:playlist:'+id,offset:0,limit:100,enableWatchFeedEntrypoint:true},snapshot),value=root.data?.playlistV2??root.data?.playlist;
      if(value){const items=arr(value.content??value.tracks),tracks=await this.hydrate(items,snapshot);const count=Number(value.content?.totalCount??tracks.length);
        for(let offset=items.length;offset<count&&offset<10000;offset+=100){const page=await this.session.graph('fetchPlaylistContents',{uri:'spotify:playlist:'+id,offset,limit:100},snapshot),extra=arr((page.data?.playlistV2??page.data?.playlist)?.content);if(!extra.length)break;tracks.push(...await this.hydrate(extra,snapshot));}return playlist(value,tracks);}
    }catch(error){this.session.check(snapshot);}
    const value=await this.session.rest('/playlists/'+id,{market:'from_token'},snapshot);const items=arr(value.tracks?.items);let next=value.tracks?.next,pages=0;
    while(next&&items.length<10000&&pages++<200){if(!/^https:\/\/api\.spotify\.com\/v1\//.test(next))throw Error('INVALID_CONTINUATION');const page=await this.session.json(next,{},snapshot);items.push(...arr(page.items));next=page.next;}return playlist(value,items.map(track).filter(Boolean));
  }
  async artist(id) {
    id=entityId(id,'artist');if(!id)return null;const snapshot=this.session.snapshot();
    try {
      const root=await this.session.graph('queryArtistOverview',{uri:'spotify:artist:'+id,locale:await this.session.host.settings.get('contentLanguage')||this.session.host.locale?.().language||'en',includePrerelease:true},snapshot),value=root.data?.artistUnion??root.data?.artist;
      if(value){const tracks=arr(value.discography?.topTracks).map(track).filter(Boolean),albums=arr(value.discography?.popularReleasesAlbums??value.discography?.popularReleases).flatMap(x=>arr(x.releases).length?arr(x.releases):[x]).map(x=>album(x)).filter(Boolean);
        const result=artist(value,tracks,albums);if(result)result.metadata={...result.metadata,relatedArtists:arr(value.relatedContent?.relatedArtists).map(x=>artist(x)).filter(Boolean),
          popularReleases:albums,featuring:arr(value.relatedContent?.appearsOn).flatMap(x=>arr(x.releases)).map(x=>album(x)).filter(Boolean),artistPlaylists:arr(value.profile?.playlistsV2).map(x=>playlist(x)).filter(Boolean),
          discoveredOn:arr(value.relatedContent?.discoveredOnV2).map(x=>playlist(x)).filter(Boolean),featuringPlaylists:arr(value.relatedContent?.featuringV2).map(x=>playlist(x)).filter(Boolean),
          concerts:arr(value.goods?.concerts).map(unwrap).filter(x=>x?.title&&x?.uri).map(x=>({title:x.title,uri:x.uri,city:x.location?.city,venue:x.location?.name,startDateIso:x.startDateIsoString})),
          merch:arr(value.goods?.merch).filter(x=>x?.nameV2&&x?.url).map(x=>({name:x.nameV2,url:x.url,price:x.price,imageUrl:arr(x.image?.sources)[0]?.url})),discography:value.discography,spotify:value};return result;}
    }catch(error){this.session.check(snapshot);}
    const [value,top,releases]=await Promise.all([this.session.rest('/artists/'+id,{},snapshot),this.session.rest('/artists/'+id+'/top-tracks',{market:'from_token'},snapshot),this.session.rest('/artists/'+id+'/albums',{include_groups:'album,single,compilation',limit:50},snapshot)]);
    return artist(value,arr(top.tracks).map(track).filter(Boolean),arr(releases.items).map(x=>album(x)).filter(Boolean));
  }
  async home() {
    const snapshot=this.session.snapshot(),spT=snapshot.cookie.split('; ').find(x=>x.startsWith('sp_t='))?.slice(5);
    const systemTimeZone=this.session.host.locale?.().timeZone||(typeof Intl==='object'?Intl.DateTimeFormat().resolvedOptions().timeZone:'UTC');
    try {const root=await this.session.graph('home',{timeZone:await this.session.host.settings.get('timeZone')||systemTimeZone,sp_t:spT,facet:'',sectionItemsLimit:20},snapshot),shelves=homeShelves(root);if(shelves.length)return {shelves};}
    catch(error){this.session.check(snapshot);}
    const requests=[['Recently played','/me/player/recently-played',v=>track(v)],['Your playlists','/me/playlists',v=>playlist(v)],['Your top tracks','/me/top/tracks',v=>track(v)]];
    const shelves=[];for(const [title,path,parse]of requests){try{const page=await this.session.rest(path,{limit:10,...(path.includes('/top/')?{time_range:'short_term'}:{})},snapshot),items=arr(page.items).map(parse).filter(Boolean).map(v=>path.includes('playlists')?{playlist:v}:{track:v});if(items.length)shelves.push({title,items});}catch(error){this.session.check(snapshot);}}
    return {shelves};
  }
  async library(request) {
    const snapshot=this.session.snapshot(),category=request.likedSongsOnly?'likes':'library';let cursor={section:0,offset:0,revision:snapshot.revision,category};
    if(request.continuation){if(typeof request.continuation!=='string'||request.continuation.length>512||!request.continuation.startsWith('spotify-library:'))throw Error('INVALID_CONTINUATION');cursor=JSON.parse(request.continuation.slice(16));if(cursor.revision!==snapshot.revision||cursor.category!==category||!Number.isInteger(cursor.section)||cursor.section<0||cursor.section>3||!Number.isInteger(cursor.offset)||cursor.offset<0)throw Error('INVALID_CONTINUATION');}
    const categories=request.likedSongsOnly?['/me/tracks']:['/me/playlists','/me/albums','/me/following','/me/tracks'];
    const path=categories[cursor.section];if(!path)throw Error('INVALID_CONTINUATION');
    let page;
    try {
      const likes=path==='/me/tracks',root=await this.session.graph(likes?'fetchLibraryTracks':'libraryV3',likes?{uri:'spotify:collection:tracks',offset:cursor.offset,limit:50}:{filters:[['Playlists','Albums','Artists'][cursor.section]],order:null,textFilter:'',features:['LIKED_SONGS'],limit:50,offset:cursor.offset,flatten:false,expandedFolders:[],folderUri:null,includeFoldersWhenFlattening:true,withCuration:false},snapshot);
      const data=likes?root.data?.me?.library?.tracks:root.data?.me?.libraryV3;
      if(!data||!Array.isArray(data.items))throw Error('SPOTIFY_LIBRARY_UNAVAILABLE');
      const raw=data.items.map(unwrap);page={items:raw.map(v=>path==='/me/albums'?{album:v}:v),next:raw.length&&(Number.isFinite(Number(data.totalCount))?cursor.offset+raw.length<Number(data.totalCount):raw.length===50)};
    } catch(error) {this.session.check(snapshot);const root=await this.session.rest(path,{limit:50,offset:cursor.offset,...(path==='/me/following'?{type:'artist',after:cursor.after}: {})},snapshot);page=path==='/me/following'?root.artists:root;}

    const items=arr(page.items).map(v=>path==='/me/tracks'?{track:(()=>{const t=track(v);return t?{...t,extras:{...t.extras,likeStatus:'LIKE'}}:null;})()}:path==='/me/albums'?{album:album(v.album)}:path==='/me/following'?{artist:artist(v)}:{playlist:v?.uri==='spotify:collection:tracks'?{id:v.uri,title:'Liked Songs',author:'Spotify',artwork:picture(v),tracks:[]}:playlist(v)}).filter(v=>Object.values(v)[0]);
    let next=page.next?{...cursor,offset:cursor.offset+50,after:page.cursors?.after}:cursor.section+1<categories.length?{...cursor,section:cursor.section+1,offset:0}:null;
    return {items,continuation:next?'spotify-library:'+JSON.stringify(next):null,complete:next===null};
  }
  async liked(request) {
    const id=entityId(request.trackId),snapshot=this.session.snapshot();if(!id)return false;if(!['LIKE','INDIFFERENT'].includes(request.vote))return false;
    try{await this.session.graph(request.vote==='LIKE'?'addToLibrary':'removeFromLibrary',{uris:['spotify:track:'+id]},snapshot);return true;}
    catch(error){this.session.check(snapshot);await this.session.rest('/me/tracks',{ids:id},snapshot,{method:request.vote==='LIKE'?'PUT':'DELETE'});return true;}
  }
  async resolve(request) {
    const direct=identityId(request.identity)??entityId(request.track?.id)??entityId(request.id),snapshot=this.session.snapshot();if(direct){try{const root=await this.session.graph('decorateContextTracks',{uris:['spotify:track:'+direct]},snapshot);const value=arr(root.data?.tracks).map(track).find(v=>entityId(v?.id)===direct);if(value){if(!value.extras.isrc){try{const rest=await this.session.rest('/tracks/'+direct,{market:'from_token'},snapshot);if(entityId(rest?.uri??rest?.id)===direct){const enriched=track(rest);if(enriched)return {...value,...enriched,extras:{...value.extras,...enriched.extras}};}}catch(error){this.session.check(snapshot);}}return value;}}catch(error){this.session.check(snapshot);}const value=await this.session.rest('/tracks/'+direct,{market:'from_token'},snapshot);if(entityId(value?.uri??value?.id)!==direct)throw Error('SPOTIFY_TRACK_ID_MISMATCH');return track(value);}
    const base=request.base?.fields??{},field=k=>base[k]?.value;
    const expected=request.track??{title:field('title'),artist:field('artist'),album:field('album'),duration:Number(field('duration')),isrc:request.identity?.isrc};
    const query=expected.isrc?'isrc:'+expected.isrc:[expected.title,expected.artist].filter(Boolean).join(' ');if(!query)return null;
    const candidates=(await this.search(query,10)).tracks;this.session.check(snapshot);return matchTrack(candidates,{...expected,isrc:expected.isrc??request.identity?.isrc});
  }
  async canvas(request) {
    const resolved=request.id?{id:request.id}:request.track&&entityId(request.track.id)?request.track:await this.resolve(request),id=entityId(resolved?.id);if(!id)return {};
    const snapshot=this.session.snapshot(),uri='spotify:track:'+id,key=snapshot.revision+':'+uri,cached=this.canvasCache.get(key);
    if(cached&&cached.expires>Date.now())return cached.value;
    let url=null;
    try {
      const response=await this.session.auth('https://spclient.wg.spotify.com/canvaz-cache/v0/canvases',{method:'POST',body:base64(protoBytes(1,protoString(1,uri))),bodyEncoding:'base64',responseType:'base64',headers:{Accept:'application/protobuf','Content-Type':'application/x-www-form-urlencoded'},maxBytes:524288},snapshot);
      if(response.bodyEncoding==='base64')url=parseCanvas(unbase64(response.body),uri);
    }catch(error){this.session.check(snapshot);}
    if(!url){try{const root=await this.session.graph('canvas',{trackUri:uri},snapshot),canvas=root.data?.trackUnion?.canvas;if(String(canvas?.type??'').startsWith('VIDEO')&&usableCanvas(canvas.url))url=canvas.url;}catch(error){this.session.check(snapshot);}}
    const value=url?{url,headers:{},trackUri:uri}:{};this.canvasCache.set(key,{value,expires:Date.now()+(url?21600000:300000)});while(this.canvasCache.size>100)this.canvasCache.delete(this.canvasCache.keys().next().value);return value;
  }
  async lyrics(request) {
    const resolved=await this.resolve({identity:request.identity,track:{title:request.title,artist:request.artist,duration:request.duration}}),id=entityId(resolved?.id);if(!id)return null;
    return lyrics(await this.session.json('https://spclient.wg.spotify.com/color-lyrics/v2/track/'+id+'?format=json&vocalRemoval=false&market=from_token'));
  }
  async mix(id) {id=entityId(id);if(!id)return {};const snapshot=this.session.snapshot();try{const value=await this.session.rest('/audio-features/'+id,{},snapshot);return {bpm:Number(value.tempo)>=40&&Number(value.tempo)<=240?String(value.tempo):undefined,key:Number.isInteger(value.key)&&value.key>=0&&value.key<=11?String(value.key):undefined,mode:[0,1].includes(value.mode)?String(value.mode):undefined};}catch(error){this.session.check(snapshot);return {};}}
  async recent(limit=50) {const root=await this.session.rest('/me/player/recently-played',{limit:Math.max(1,Math.min(Number(limit)||50,50))});return {tracks:arr(root.items).map(track).filter(Boolean)};}
}
