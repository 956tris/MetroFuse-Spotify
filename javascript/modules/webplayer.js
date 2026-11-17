/* Spotify Web Player queue/state and history protocols ported from legacy MetroFuse. */
import {entityId,track} from './catalog.js';
import {unbase64} from './encoding.js';

const SDK='harmony:4.72.0',PLATFORM='web_player windows 10;chrome 148.0.0.0;desktop',CLIENT='65b708073fc0480ea92a077233ca87bd';
const randomId=(length=32)=>Array.from({length},()=>Math.floor(Math.random()*16).toString(16)).join('');
export function machineTrack(value) {
  if(!value)return null;const meta=value.metadata??value;
  const uri=value.uri??value.track_uri??value.trackUri??meta.track_uri??meta.trackUri??meta.entity_uri??meta.entityUri??meta.uri;
  if(String(uri??'').startsWith('spotify:ad:')||/^(ad|advertisement)$/i.test(value.type??meta.type??''))return null;
  let id=entityId(uri)??entityId(value.id);
  if(!id){try{const gid=value.gid??meta.gid;const bytes=/^[a-f0-9]{32}$/i.test(gid??'')?gid.match(/../g).map(x=>parseInt(x,16)):unbase64(gid);if(bytes.length===16){let n=bytes.reduce((n,b)=>n*256n+BigInt(b),0n),out='';const alphabet='0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';do{out=alphabet[Number(n%62n)]+out;n/=62n;}while(n);id=out.padStart(22,'0');}}catch{}}
  if(!id)return null;
  const artists=Array.isArray(meta.artists)?meta.artists:Array.isArray(value.artists)?value.artists:[{name:meta.artist_name??meta.artist??meta.artistName}].filter(x=>x.name);
  const album={name:meta.group_name??meta.album_name??meta.albumName??meta.album??value.album?.name??'',uri:meta.group_uri??meta.album_uri??value.album?.uri,
    images:[{url:meta.image_url??meta.imageUrl??meta.image??meta.cover_url??value.album?.images?.[0]?.url??''}]};
  const duration=Number(meta.duration??meta.duration_ms??value.duration_ms??0);
  return track({uri:'spotify:track:'+id,name:meta.name??meta.title??value.name??value.title,artists,album,duration_ms:duration,explicit:meta.is_explicit===true||meta.explicit===true||meta.explicit==='true'});
}
export function stateMachineQueue(machine,reference) {
  const states=machine?.states,tracks=machine?.tracks,index=Number(reference?.state_index);
  if(!Array.isArray(states)||!Array.isArray(tracks)||!Number.isInteger(index)||index<0||index>=states.length)return null;
  const current=states[index],stateId=current?.state_id,machineId=machine.state_machine_id;if(!machineTrack(tracks[current?.track]))return null;
  const result=[],seen=new Set();let cursor=index,malformed=false;
  while(Number.isInteger(cursor)&&cursor>=0&&cursor<states.length&&!seen.has(cursor)&&result.length<100) {
    seen.add(cursor);const state=states[cursor],item=machineTrack(tracks[state?.track]);if(!item){malformed=true;break;}result.push(item);
    const next=state.transitions?.show_next;cursor=next?.state_index;if(next&&(!Number.isInteger(cursor)||cursor<0||cursor>=states.length)){malformed=true;break;}
  }
  if(!result.length||!stateId||!machineId)return null;
  const raw=tracks[current.track];
  return {tracks:result,currentIndex:0,complete:!malformed&&cursor==null,
    stateMachineId:machineId,stateId,paused:reference.paused===true,playbackId:raw?.logData?.playbackId??raw?.log_data?.playback_id,
    durationMs:Number(current.duration_override??raw?.metadata?.duration??result[0].duration)};
}
export function clusterQueue(value) {
  const state=value?.cluster?.player_state??value?.player_state;if(!state?.track)return null;
  const current=machineTrack(state.track);if(!current)return null;
  if(!Array.isArray(state.next_tracks))return null;const upcoming=state.next_tracks.map(machineTrack);if(upcoming.some(item=>!item))return null;const tracks=[current,...upcoming];
  return {tracks,currentIndex:0,contextUri:state.context_uri??state.context?.uri,complete:true};
}
export function dealerMessage(message) {
  let root;try{root=typeof message==='string'?JSON.parse(message):message;}catch{return {};}
  if(root?.type==='ping')return {ping:true};
  if(root?.type!=='message')return {};
  const connectionId=root.headers?.['Spotify-Connection-Id']??/hm:\/\/pusher\/(?:[^/]+\/)?connections\/([^/?#]+)/.exec(root.uri??'')?.[1];
  const payload=root.payloads?.[0],command=root.uri==='hm://track-playback/v1/command'&&payload?.type==='replace_state'?payload:null;
  const cluster=String(root.uri??'').endsWith('connect-state/v1/cluster')?payload?.cluster:null;
  return {connectionId:connectionId?decodeURIComponent(connectionId):undefined,command,cluster};
}
export function statePayload(session,{position,previousPosition,paused,source='started_playing',stats=false},sequence) {
  const payload={seq_num:sequence,debug_source:source,previous_position:Math.max(0,previousPosition),
    state_ref:{state_machine_id:session.stateMachineId,state_id:session.stateId,paused},
    sub_state:{playback_speed:paused?0:1,position:Math.max(0,position),duration:Math.max(0,session.durationMs),media_type:'AUDIO',bitrate:128000,audio_quality:'HIGH',format:'file_ids_mp4',is_video_on:false}};
  if(stats)payload.playback_stats={ms_total_est:Math.max(0,session.durationMs),ms_metadata_duration:0,ms_manifest_latency:0,ms_latency:98};return payload;
}
export function historyEvents(session,kind,position=0,nextPlaybackId='',endPosition=position) {
  const common={play_track:session.trackUri,playback_id:session.playbackId,session_id:session.sessionId};
  if(kind==='start')return [{type:'jssdk_playback_start',message:{...common,file_id:'',ms_start_position:0,initially_paused:false,client_id:CLIENT,correlation_id:session.correlationId,feature_identifier:'web-player'}}];
  if(kind!=='finalize')return [];
  const stats={...common,file_id:'',internal_play_id:session.playbackId,memory_cached:false,persistent_cached:false,audio_format:'',video_format:'',manifest_id:'',protected:false,key_system:'',key_system_impl:'',urls_json:'[]',
    start_time:session.startedAtMs,end_time:session.startedAtMs+position,external_start_time:session.startedAtMs,ms_played:position,ms_nominal_played:position,ms_file_duration:session.durationMs,ms_actual_duration:session.durationMs,
    ms_start_position:0,ms_end_position:endPosition,ms_played_per_surface:{},ms_played_visible:position,audio_start_bitrate:128000,start_bitrate:128000,audio_quality:'',time_weighted_bitrate:128000,reason_start:'playbtn',reason_end:'endplay',initially_paused:false,had_error:false,
    sequence_id:1,client_id:CLIENT,correlation_id:session.correlationId,strategy:'',ms_played_per_audio_format:{},ms_played_per_video_format:{}};
  for(const key of ['ms_play_latency','ms_init_latency','ms_head_latency','ms_first_bytes_latency','ms_manifest_latency','ms_resolve_latency','ms_license_session_latency','ms_license_generation_latency','ms_license_request_latency','ms_license_update_latency','ms_metadata_duration','ms_initial_rebuffer','ms_seek_rebuffer','ms_seek_rebuffer_longest','ms_stall_rebuffer','ms_stall_rebuffer_longest','n_stalls','n_rendition_upgrade','n_rendition_downgrade','bps_bandwidth_max','bps_bandwidth_min','bps_bandwidth_avg','n_seekback','n_seekforward','video_start_bitrate','n_warnings','n_navigator_offline','n_dropped_video_frames','n_total_video_frames','resolution_max','resolution_min','total_bytes'])stats[key]=0;
  return [{type:'jssdk_playback_stats',message:stats},{type:'track_stream_verification',message:{...common,ms_played:position,ms_nominal_played:position,sequence_id:1,next_playback_id:nextPlaybackId,playback_service:'web_player'}}];
}
export class SpotifyWebPlayer {
  constructor(session,catalog) {this.auth=session;this.catalog=catalog;this.host=session.host;this.device=null;this.current=null;this.queueCache=null;this.openPromise=null;this.commandWaiters=[];this.commands=[];this.blockedUntil=0;this.tail=Promise.resolve();this.stopped=false;this.lastSnapshot=null;this.lastSessionTime=0;}
  check(snapshot) {this.auth.check(snapshot);if(this.stopped)throw Error('PLUGIN_STOPPED');}
  enqueue(fn) {const result=this.tail.then(fn);this.tail=result.catch(error=>{if(error.message!=='ACCOUNT_CHANGED'&&error.message!=='PLUGIN_STOPPED')this.host.log('Spotify Web Player operation failed');});return result;}
  async post(path,body,snapshot,method='POST') {return this.auth.json(this.device.webgate+path,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)},snapshot);}
  async open(snapshot=this.auth.snapshot()) {
    this.check(snapshot);if(Date.now()<this.blockedUntil)throw Error('SPOTIFY_HISTORY_BACKOFF');
    if(this.device?.revision===snapshot.revision&&Date.now()-this.device.created<2700000)return this.device;
    if(this.openPromise)return this.openPromise;
    const promise=(async()=>{
      await this.closeDevice();this.check(snapshot);
      if(!this.host.socket?.open||!this.host.socket?.receive)throw Error('SDK_WEBSOCKET_REQUIRED');
      const endpoints=await this.auth.publicJson('https://apresolve.spotify.com/?type=dealer-g2&type=spclient');
      const dealer=endpoints['dealer-g2']?.[0],webgate=endpoints.spclient?.[0];
      if(typeof dealer!=='string'||typeof webgate!=='string'||![dealer,webgate].every(x=>/^[A-Za-z0-9.-]+\.spotify\.com(?::443)?$/.test(x)))throw Error('INVALID_SPOTIFY_ENDPOINT');
      const token=await this.auth.token(snapshot),id=await this.host.socket.open({url:'wss://'+dealer.replace(/:443$/,'')+'?access_token='+encodeURIComponent(token)});
      try{this.check(snapshot);}catch(error){await this.host.socket.close(id);throw error;}
      const device={id,revision:snapshot.revision,webgate:'https://'+webgate.replace(/:443$/,''),deviceId:randomId(40),created:Date.now(),sequence:0,unsubscribe:null};this.device=device;
      try {
        const deadline=Date.now()+10000;
        while(!device.connectionId&&Date.now()<deadline)await this.read(device,deadline-Date.now(),snapshot);
        if(!device.connectionId)throw Error('SPOTIFY_DEALER_TIMEOUT');const connectionId=device.connectionId;this.check(snapshot);
        const observer={member_type:'CONNECT_STATE',device:{device_info:{capabilities:{can_be_player:false,hidden:true,needs_full_player_state:true}}}};
        try{await this.auth.json(device.webgate+'/connect-state/v1/devices/hobs_'+device.deviceId.slice(0,35),{method:'PUT',headers:{'Content-Type':'application/json','X-Spotify-Connection-Id':connectionId},body:JSON.stringify(observer)},snapshot);}catch(error){this.check(snapshot);}
        const payload={device:{brand:'SpotifyHarmonyGeneric',capabilities:{change_volume:true,enable_play_token:true,supports_file_media_type:true,play_token_lost_behavior:'pause',disable_connect:false,audio_podcasts:true,manifest_formats:['file_ids_mp3','file_urls_mp3','file_ids_mp4','file_ids_mp4_dual'],supports_preferred_media_type:true,supports_playback_offsets:true,supports_playback_speed:true},device_id:device.deviceId,device_type:'computer',metadata:{},model:'harmony-4.72.0-web-player',name:'Spotify Web Player',platform_name:PLATFORM,platform_identifier:PLATFORM,is_group:false,correlation_id:randomId(),client_version:SDK},outro_endcontent_snooping:false,connection_id:connectionId,client_version:SDK,volume:65535};
        const response=await this.post('/track-playback/v1/devices',payload,snapshot);this.check(snapshot);device.sequence=Number(response.initial_seq_num)||0;return device;
      }catch(error){if(this.device===device)await this.closeDevice();throw error;}
    })();this.openPromise=promise;
    try{return await promise;}finally{if(this.openPromise===promise)this.openPromise=null;}
  }
  async read(device,timeoutMs,snapshot) {
    const event=await this.host.socket.receive(device.id,Math.max(0,Math.min(timeoutMs,10000)));this.check(snapshot);
    if(!event)return false;if(event.type!=='text')throw Error('SPOTIFY_DEALER_CLOSED');
    const message=dealerMessage(event.text);if(message.ping)await this.host.socket.send(device.id,JSON.stringify({type:'pong'}));
    if(message.connectionId)device.connectionId=message.connectionId;
    if(message.cluster){const queue=clusterQueue(message.cluster);if(queue)this.queueCache={...queue,revision:snapshot.revision,at:Date.now()};}
    if(message.command){const queue=stateMachineQueue(message.command.state_machine,message.command.state_ref);if(queue)this.queueCache={...queue,revision:snapshot.revision,at:Date.now()};this.commands.push(message.command);while(this.commands.length>16)this.commands.shift();}
    return true;
  }
  async waitCommand(snapshot) {
    const deadline=Date.now()+10000;
    while(!this.commands.length&&Date.now()<deadline)await this.read(this.device,deadline-Date.now(),snapshot);
    if(!this.commands.length)throw Error('SPOTIFY_DEALER_TIMEOUT');return this.commands.shift();
  }
  async requestState(item,snapshot,contextUri) {
    const device=await this.open(snapshot),id=entityId(item.id);if(!id)throw Error('SPOTIFY_TRACK_REQUIRED');
    const uri='spotify:track:'+id,contexts=[contextUri,uri,'spotify:station:track:'+id].filter((v,i,all)=>v&&all.indexOf(v)===i);
    for(const context of contexts) {
      try {
        await this.post('/connect-state/v1/player/command/from/'+device.deviceId+'/to/'+device.deviceId,
          {command:{endpoint:'play',context:{uri:context,url:'context://'+context},play_origin:{feature_identifier:'web-player',feature_version:SDK},options:{license:'',skip_to:{track_uri:uri},initially_paused:false},logging_params:{page_instance_ids:[],interaction_ids:[],command_id:randomId()}}},snapshot);
        let command=await this.waitCommand(snapshot);this.check(snapshot);
        for(let n=0;n<8;n++) {
          const parsed=stateMachineQueue(command.state_machine,command.state_ref);
          if(parsed&&entityId(parsed.tracks[0].id)===id){this.queueCache={...parsed,contextUri:context,revision:snapshot.revision,at:Date.now()};return parsed;}
          command=await this.waitCommand(snapshot);this.check(snapshot);
        }
      }catch(error){this.check(snapshot);if(error.message==='SPOTIFY_HTTP_429'){this.blockedUntil=Date.now()+120000;throw error;}}
    }throw Error('SPOTIFY_PLAYBACK_STATE_UNAVAILABLE');
  }
  async fetchQueue(snapshot,expectedId) {
    await this.open(snapshot);const root=await this.auth.json(this.device.webgate+'/connect-state/v1/cluster',{},snapshot),queue=clusterQueue(root);
    if(queue&&(!expectedId||entityId(queue.tracks[0].id)===expectedId)){this.queueCache={...queue,revision:snapshot.revision,at:Date.now()};return queue;}return null;
  }
  async queue(request={}) {
    const snapshot=this.auth.snapshot();let item=request.track??(request.identity?await this.catalog.resolve(request):this.host.playerState()?.track);
    if(item&&!entityId(item.id))item=await this.catalog.resolve({track:item,identity:request.identity});this.check(snapshot);const id=entityId(item?.id);
    if(request.track&&!id)return {tracks:[],source:'spotify-web-player',complete:false};
    let cached=this.queueCache?.revision===snapshot.revision&&Date.now()-this.queueCache.at<600000?this.queueCache:null;
    if(request.refresh||!cached||(id&&entityId(cached.tracks[0].id)!==id)) {
      await this.open(snapshot);cached=await this.fetchQueue(snapshot,id);
      if(!cached&&id)cached=await this.requestState(item,snapshot,request.contextUri??item.extras?.['spotify.contextUri']);
    }
    if(!cached)return {tracks:[],source:'spotify-web-player',complete:false};
    return {tracks:cached.tracks.slice((cached.currentIndex??0)+1),current:cached.tracks[cached.currentIndex??0],contextUri:cached.contextUri,source:'spotify-web-player',complete:cached.complete};
  }
  async reportState(data,snapshot) {
    const current=this.current;if(!current)return;
    const device=await this.open(snapshot),payload=statePayload(current,data,++device.sequence);
    const response=await this.post('/track-playback/v1/devices/'+device.deviceId+'/state',payload,snapshot,'PUT');this.check(snapshot);
    const parsed=stateMachineQueue(response.state_machine,response.updated_state_ref);
    if(parsed&&entityId(parsed.tracks[0].id)===entityId(current.trackUri)){current.stateMachineId=parsed.stateMachineId;current.stateId=parsed.stateId;current.playbackId=parsed.playbackId??current.playbackId;this.queueCache={...parsed,revision:snapshot.revision,at:Date.now()};}
    try{await this.fetchQueue(snapshot,entityId(current.trackUri));}catch(error){this.check(snapshot);}
  }
  async batch(events,snapshot) {
    await this.auth.auth('https://gew1-spclient.spotify.com/melody/v1/msg/batch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({client_version:'0.0.0',platform:PLATFORM,sdk_id:SDK,messages:events})},snapshot);
  }
  async begin(item,playback,snapshot) {
    if(typeof playback.playbackInstanceId!=='string'||!playback.playbackInstanceId.trim())return;
    const resolved=entityId(item.id)?item:await this.catalog.resolve({track:item,identity:{canonicalId:item.id,isrc:item.extras?.isrc}});this.check(snapshot);if(!resolved)return;
    const state=await this.requestState(resolved,snapshot,item.extras?.['spotify.contextUri']);this.check(snapshot);
    const now=Date.now();this.lastSessionTime=Math.max(now,this.lastSessionTime+1);this.current={trackUri:resolved.id,hostTrackId:item.id,occurrence:playback.playbackInstanceId,
      stateMachineId:state.stateMachineId,stateId:state.stateId,playbackId:state.playbackId??randomId(),sessionId:String(this.lastSessionTime),correlationId:randomId(),startedAtMs:now,durationMs:state.durationMs||resolved.duration,
      lastPosition:Number(playback.position)||0,lastTime:now,playing:playback.playing,playedMs:0,reported:false};
    await this.reportState({position:this.current.lastPosition,previousPosition:0,paused:!playback.playing,source:'before_track_load',stats:false},snapshot);
    await this.batch(historyEvents(this.current,'start'),snapshot);
    await this.reportState({position:this.current.lastPosition,previousPosition:this.current.lastPosition,paused:!playback.playing,source:'started_playing',stats:false},snapshot);
  }
  async finish(snapshot) {
    const current=this.current;if(!current||current.finalized)return;this.check(snapshot);current.finalized=true;
    const position=Math.min(current.durationMs,Math.max(0,current.lastPosition));
    try{await this.batch(historyEvents(current,'finalize',current.playedMs,'',position),snapshot);await this.reportState({position,previousPosition:position,paused:true,source:'track_data_finalized',stats:true},snapshot);}finally{if(this.current===current)this.current=null;}
  }
  async observe(playback) {
    if(this.stopped||!this.auth.cookie)return;
    const snapshot=this.auth.snapshot();this.check(snapshot);
    if(this.device)for(let n=0;n<32;n++){if(!await this.read(this.device,0,snapshot))break;}
    const enabled=await this.host.settings.get('historyEnabled');this.check(snapshot);if(!enabled){if(this.current){this.current=null;await this.closeDevice();}return;}
    const item=playback?.track;if(!item?.id){await this.finish(snapshot);return;}
    const occurrence=typeof playback.playbackInstanceId==='string'&&playback.playbackInstanceId.trim()?playback.playbackInstanceId:null;
    if(!occurrence){if(!playback.playing)await this.finish(snapshot);return;}
    if(this.current&&(this.current.hostTrackId!==item.id||(occurrence!=null&&this.current.occurrence!==occurrence)))await this.finish(snapshot);
    if(!this.current){if(playback.playing)await this.begin(item,playback,snapshot);return;}
    const current=this.current,now=Date.now(),position=Math.max(0,Number(playback.position)||0),previous=current.lastPosition,delta=position-previous;
    if(current.playing)current.playedMs+=Math.min(Math.max(0,delta),Math.max(0,now-current.lastTime)+1500);
    const sought=Math.abs(delta-(current.playing?now-current.lastTime:0))>1500;
    const changed=current.playing!==playback.playing;
    current.lastPosition=position;current.lastTime=now;current.playing=playback.playing;
    if(sought)await this.reportState({position,previousPosition:previous,paused:!playback.playing,source:'seek',stats:false},snapshot);
    else if(changed)await this.reportState({position,previousPosition:previous,paused:!playback.playing,source:playback.playing?'playback_started':'playback_paused',stats:false},snapshot);
    const threshold=Math.min(50000,Math.max(0,current.durationMs*0.5));
    if(!current.reported&&current.durationMs>=30000&&current.playedMs>=threshold){await this.reportState({position,previousPosition:position,paused:!playback.playing,source:'played_threshold_reached',stats:false},snapshot);current.reported=true;}
  }
  async closeDevice() {
    const device=this.device;this.device=null;this.commands=[];
    for(const waiter of this.commandWaiters.splice(0))waiter.reject(Error('ACCOUNT_CHANGED'));
    if(device){device.unsubscribe?.();try{await this.host.socket.close(device.id);}catch{}}
  }
  async reset() {this.current=null;this.queueCache=null;this.lastSnapshot=null;await this.closeDevice();}
  async stop() {this.stopped=true;this.current=null;this.queueCache=null;await this.closeDevice();}
}
