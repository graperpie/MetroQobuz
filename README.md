# MetroQobuz

Qobuz backend replacement for Metrolist
### NOTE: 
- do *NOT* ask for help/support from the main developers regarding this fork. most issues caused by this fork is likely my fault. for support please ping me in the metrolist discord server or add me directly at @graperpie.
- this fork has been created under heavy usage of AI. if you are against using "AI-slop", please refrain from using it (to maintain your sanity lmao)
---

## Features
- Qobuz backend (obviously lmao)
- FLAC streaming support
- AAC 320kbps, CD Quality, and Hi‑Res playback (on Hi-Res-supported songs)
- Apple animated artwork cover (thanks tris!)
- Basic quality label on top of song title in player (specific info underneath the seek bar)

---

## Known Issues
- Occasional ExoPlayer crashes (i dont know why lol)
- ~~Caching does not work (afaik)~~ it apparently does, it's just unoptimised and slow
- lyrics may appear off-sync (not much i can do about this until further notice)
- (rarely) select few songs can actually end up playing the wrong song, this is my fault, but i've tried to tune this to the best of my ability to prevent this
- not all songs are listed in qobuz, so they either don't play at all or fallback to youtube
- amazingly, that’s it for now


---

## Credits
- 956tris for developing the initial framework, and for the Apple Music animated artwork in his [MetroApple](https://github.com/956tris/MetroApple) fork
- the Metrolist team for creating this wonderful app
