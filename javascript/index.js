/* Ported from MetroFuse/MetroList legacy Spotify integration. GPL-3.0; see SOURCE. */
import {SpotifySession,loginRequest,loginUi} from './modules/account.js';
import {SpotifyCatalog,entityId} from './modules/catalog.js';
import {SpotifyWebPlayer} from './modules/webplayer.js';
let host,session,catalog,webplayer,unsubscribe,lastQueueOccurrence,lastQueueKey,lastQueueRefresh=0;

async function artistMetadata(request) {
  const value=await catalog.artist(request.id);if(!value)return {};
  return {...value,...value.metadata,popularReleases:value.metadata?.popularReleases??value.albums,
    relatedArtists:value.metadata?.relatedArtists??[],playlists:[...(value.metadata?.artistPlaylists??[]),...(value.metadata?.featuringPlaylists??[])],concerts:value.metadata?.concerts??[],merch:value.metadata?.merch??[]};
}
async function playbackChanged(playback,revision) {
  if(!session.cookie||revision!==session.revision)return;
  await webplayer.observe(playback);
  if(!session.cookie||revision!==session.revision||!await host.settings.get('queueSyncEnabled'))return;
  if(!host.playerCapabilities?.().includes('replaceUpcomingQueue'))return;
  const occurrence=playback?.playbackInstanceId??playback?.currentIndex??playback?.queueIndex??playback?.track?.id;
  if(!playback?.track||!playback.playing)return;
  const snapshot=session.snapshot();
  const refresh=occurrence!==lastQueueOccurrence||Date.now()-lastQueueRefresh>=15000;
  const result=await webplayer.queue({track:playback.track,refresh});session.check(snapshot);if(refresh)lastQueueRefresh=Date.now();
  const key=JSON.stringify([occurrence,result.current?.id,result.contextUri,result.tracks]);
  if(result.complete&&result.current&&key!==lastQueueKey){await host.replaceUpcomingQueue(result.tracks);lastQueueOccurrence=occurrence;lastQueueKey=key;}
}

globalThis.plugin={
  async init(context) {
    host=context;session=new SpotifySession(host);catalog=new SpotifyCatalog(session);webplayer=new SpotifyWebPlayer(session,catalog);
    host.login({request:async()=>loginRequest,ui:async()=>loginUi,
      login:async credentials=>{
        session.clear();lastQueueOccurrence=null;lastQueueKey=null;lastQueueRefresh=0;catalog.canvasCache.clear();await webplayer.reset();
        const state=await session.verify(credentials?.values?.cookie);host.loginState(state);return state;
      },logout:async()=>{session.clear();lastQueueOccurrence=null;lastQueueKey=null;lastQueueRefresh=0;catalog.canvasCache.clear();await webplayer.reset();host.loginState({loggedIn:false});}});
    host.home(()=>catalog.home());host.search('spotify.search',request=>catalog.search(request.query,request.limit));
    host.catalog('spotify.catalog',{album:id=>catalog.album(id),artist:id=>catalog.artist(id),playlist:id=>catalog.playlist(id),library:request=>catalog.library(request),supportsLikedSongs:true,setSongVote:request=>catalog.liked(request),
      recommendations:request=>webplayer.enqueue(()=>webplayer.queue(request))});
    host.metadataSource('spotify.metadata',async request=>{
      const value=await catalog.resolve(request);if(!value)return null;
      const snapshot=session.snapshot(),mix=await host.settings.get('mixMetadataEnabled')?await catalog.mix(value.id):{};
      const canvas=await host.settings.get('canvasEnabled')?await catalog.canvas({track:value}):{};session.check(snapshot);
      return {identity:{...request.identity,identifiers:{...request.identity?.identifiers,spotify:entityId(value.id)},isrc:value.extras?.isrc??request.identity?.isrc},
        fields:{title:value.title,artist:value.artist,album:value.album,duration:value.duration,artwork:value.artwork,...value.extras,...Object.fromEntries(Object.entries(mix).filter(([,v])=>v!==undefined)),...(canvas.url?{'spotify.canvas':canvas.url}:{})},confidence:1};
    });
    host.lyrics('spotify.lyrics',request=>catalog.lyrics(request));
    for(const name of ['spotify.canvas/v1','artwork.canvas/v1'])host.service(name,request=>catalog.canvas(request));
    for(const name of ['spotify.artist/v1','catalog.artist-metadata/v1'])host.service(name,artistMetadata);
    host.service('spotify.history/v1',request=>catalog.recent(request.limit));
    host.service('spotify.queue/v1',request=>webplayer.enqueue(()=>webplayer.queue(request)));
    unsubscribe=host.on('playback_changed',playback=>{const revision=session.revision;return webplayer.enqueue(()=>playbackChanged(playback,revision)).catch(()=>{});});
  },
  async dispose(){unsubscribe?.();unsubscribe=null;session?.clear();await webplayer?.stop();catalog?.canvasCache.clear();lastQueueOccurrence=null;lastQueueKey=null;lastQueueRefresh=0;}
};
