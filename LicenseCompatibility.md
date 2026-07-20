# License compatibility

This file is only a note for myself (the developer), 
to make sure that all libraries and plugins I used are 
compatible with the license of my app [GPL-3.0](https://www.gnu.org/licenses/).

## Licenses
- `Apache Software License, Version 2.0`: **compatible** (see [Apache.org](https://www.apache.org/licenses/GPL-compatibility.html))
- `Eclipse Public License - v 2.0`: **compatible** (see [Eclipse.org](https://www.eclipse.org/legal/epl-2.0/faq/#h.it3upld1gcpe))
- `SIL Open Font License, Version 1.1`: **compatible**
*(Only if the program is licensed under GNU License. Fonts have to be licensed under `SIL Open Font License, Version 1.1`)* 
(see [openfontlicense.org](https://openfontlicense.org/open-font-license-official-text/))


## This app
### `com.otaliastudios:zoomlayout`

Uses the `Apache Software License, Version 2.0` \
(see [GitHub](https://github.com/natario1/ZoomLayout))

### `com.android.application`

Uses the `Apache Software License, Version 2.0` \
(see [Maven Repository](https://mvnrepository.com/artifact/com.android.application/com.android.application.gradle.plugin/8.9.0-alpha05))

### `androidx`

Uses the `Apache Software License, Version 2.0` \
(see [GitHub.com](https://github.com/androidx/androidx?tab=Apache-2.0-1-ov-file))

### `com.google.android.material`

Uses the `Apache Software License, Version 2.0` \
(see [GitHub.com](https://github.com/material-components/material-components-android?tab=Apache-2.0-1-ov-file))

### `org.apache.commons`

Uses the `Apache Software License, Version 2.0` \
(see [Apache.org](https://www.apache.org/licenses/))

### `CheckableImageButton`

Made by Alex Korovyansky, No license provided
[Link to gist:](https://gist.github.com/AlexKorovyansky/71f673b9840519152d1d)

### `com.github.cachapa`

Uses the `Apache Software License, Version 2.0` \
(see [GitHub.com](https://github.com/cachapa/ExpandableLayout))

### `dev.misono.breaklinelayout.BreakLineLayout`

Made by Douglas Tian, No license provided
[Link to repository:](https://github.com/zerozhiqin/AutoBreakLineLayout/)

### `junit`

Uses the `Eclipse Public License - v 2.0` \
(see [GitHub.com](https://github.com/junit-team/junit-framework/tree/main?tab=EPL-2.0-1-ov-file))

## Removed dependencies

`GlyphConverter`, `MAAT`, `SignProvider` and `THOTH` (all `com.github.ThothDroid`,
resolved via `jitpack.io`) were used in an earlier, pre-Bliss version of this
app (see git history around commit "Migrated to SignProvider-Library",
2026-02-17). The current sign-rendering pipeline (`BlissSignProvider` +
`androidsvg`, see `INTEGRATION.md`) replaced them entirely, but the
dependency declarations were never cleaned up from `build.gradle.kts` /
`gradle/libs.versions.toml` until 2026-07-20, when they were confirmed
unused (zero references anywhere in `app/src/`) and removed, along with the
now-unnecessary `jitpack.io` repository. Their license-compatibility notes
(previously several sections here, one per library plus their upstream
"ExampleApp" modules) no longer apply to this app's actual dependency graph
and have been dropped from this file.

## Other content

Here are the licenses of other content used such as images, texts, videos or audio.

### Google Fonts `Noto Sans Egyptian Hieroglyphs`
I extracted the whole hieroglyphic character set from this font. \
This font is licensed under the `SIL Open Font License, Version 1.1` (see [fonts.google.com](https://fonts.google.com/noto/specimen/Noto+Sans+Egyptian+Hieroglyphs/license?lang=egy_Egyp)).\
My changed version of the font (all SVGs) are also licensed under the ` SIL Open Font License, Version 1.1`.

### Google Fonts `Anonymous Pro`
This font is used to display mdc codes. It is licensed under the ` SIL Open Font License, Version 1.1` \
(see [fonts.google.com](https://fonts.google.com/specimen/Anonymous+Pro/license))

### Google Fonts icons
The icons are licensed under `Apache Software License, Version 2.0` \
(see [font.google.com](https://fonts.google.com/icons))

### JSesh Fonts
I used the SVG file for sign `A7` 
as the basis for my Clueless Egyptian icon.
The JSesh fonts are not licensed, but the author provides instructions. (see [JSesh](https://jsesh.qenherkhopeshef.org/page/fonts_license)). \
\
It is permitted to modify the glyphs and use them under the same conditions. \
Hereby, I declare that my icon can only be used under the same conditions as the JSesh fonts.