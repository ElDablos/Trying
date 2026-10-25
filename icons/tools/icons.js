// Every icon in the pack: the drawable name, the glyph, and the launcher components it replaces.
// Glyphs come from Simple Icons (si:, CC0 brand logos) or Material Design Icons (mdi:, Apache 2.0).
// Component names are "package/activity". Several are listed where apps have used more than one.
module.exports = [
  // Samsung
  { id: 'phone', glyph: 'mdi:phone', components: ['com.samsung.android.dialer/com.samsung.android.dialer.DialtactsActivity', 'com.samsung.android.app.telephonyui/com.samsung.android.app.telephonyui.dialer.DialtactsActivity'] },
  { id: 'messages', glyph: 'mdi:message-text', components: ['com.samsung.android.messaging/com.android.mms.ui.ConversationComposer', 'com.google.android.apps.messaging/com.google.android.apps.messaging.ui.ConversationListActivity'] },
  { id: 'camera', glyph: 'mdi:camera', components: ['com.sec.android.app.camera/com.sec.android.app.camera.Camera'] },
  { id: 'gallery', glyph: 'mdi:image-multiple', components: ['com.sec.android.gallery3d/com.samsung.android.gallery.app.activity.GalleryActivity', 'com.sec.android.gallery3d/com.sec.android.gallery3d.app.GalleryActivity'] },
  { id: 'clock', glyph: 'mdi:clock-outline', components: ['com.sec.android.app.clockpackage/com.sec.android.app.clockpackage.ClockPackage'] },
  { id: 'calendar', glyph: 'mdi:calendar-month', components: ['com.samsung.android.calendar/com.samsung.android.app.calendar.activity.MainActivity'] },
  { id: 'notes', glyph: 'mdi:notebook-edit', components: ['com.samsung.android.app.notes/com.samsung.android.app.notes.memolist.MemoListActivity'] },
  { id: 'settings', glyph: 'mdi:cog', components: ['com.android.settings/com.android.settings.Settings'] },
  { id: 'weather', glyph: 'mdi:weather-partly-cloudy', components: ['com.sec.android.daemonapp/com.samsung.android.weather.app.WeatherActivity', 'com.sec.android.daemonapp/com.sec.android.daemonapp.app.MainActivity'] },
  { id: 'contacts', glyph: 'mdi:contacts', components: ['com.samsung.android.app.contacts/com.samsung.android.contacts.contactslist.PeopleActivity'] },
  { id: 'myfiles', glyph: 'mdi:view-grid-outline', components: ['com.sec.android.app.myfiles/com.sec.android.app.myfiles.external.ui.MainActivity'] },
  { id: 'galaxystore', glyph: 'mdi:shopping', components: ['com.sec.android.app.samsungapps/com.sec.android.app.samsungapps.SamsungAppsMainActivity'] },
  { id: 'samsunghealth', glyph: 'mdi:leaf', components: ['com.sec.android.app.shealth/com.samsung.android.app.shealth.home.HomeActivity', 'com.sec.android.app.shealth/com.samsung.android.app.shealth.home.SplashActivity'] },
  { id: 'goodlock', glyph: 'mdi:lock-open-variant', components: ['com.samsung.android.goodlock/com.samsung.android.goodlock.presentation.view.MainActivity'] },
  { id: 'soundassistant', glyph: 'mdi:tune-vertical', components: ['com.samsung.android.soundassistant/com.samsung.android.soundassistant.SoundAssistantMainActivity'] },
  { id: 'wearable', glyph: 'mdi:headphones', components: ['com.samsung.android.app.watchmanager/com.samsung.android.app.watchmanager.setupwizard.StartActivity'] },

  // Google
  { id: 'google', glyph: 'si:google', components: ['com.google.android.googlequicksearchbox/com.google.android.googlequicksearchbox.SearchActivity'] },
  { id: 'chrome', glyph: 'si:googlechrome', components: ['com.android.chrome/com.google.android.apps.chrome.Main'] },
  { id: 'playstore', glyph: 'si:googleplay', components: ['com.android.vending/com.android.vending.AssetBrowserActivity'] },
  { id: 'gmail', glyph: 'si:gmail', components: ['com.google.android.gm/com.google.android.gm.ConversationListActivityGmail'] },
  { id: 'maps', glyph: 'si:googlemaps', components: ['com.google.android.apps.maps/com.google.android.maps.MapsActivity'] },
  { id: 'drive', glyph: 'si:googledrive', components: ['com.google.android.apps.docs/com.google.android.apps.docs.app.NewMainProxyActivity'] },
  { id: 'photos', glyph: 'si:googlephotos', components: ['com.google.android.apps.photos/com.google.android.apps.photos.home.HomeActivity'] },
  { id: 'gemini', glyph: 'si:googlegemini', components: ['com.google.android.apps.bard/com.google.android.apps.bard.shellapp.BardEntryPointActivity'] },
  { id: 'youtube', glyph: 'si:youtube', components: ['com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity'] },
  { id: 'ytmusic', glyph: 'si:youtubemusic', components: ['com.google.android.apps.youtube.music/com.google.android.apps.youtube.music.activities.MusicActivity'] },

  // Social and messaging
  { id: 'whatsapp', glyph: 'si:whatsapp', components: ['com.whatsapp/com.whatsapp.Main'] },
  { id: 'messenger', glyph: 'si:messenger', components: ['com.facebook.orca/com.facebook.orca.auth.StartScreenActivity'] },
  { id: 'instagram', glyph: 'si:instagram', components: ['com.instagram.android/com.instagram.mainactivity.LauncherActivity', 'com.instagram.android/com.instagram.mainactivity.InstagramMainActivity'] },
  { id: 'facebook', glyph: 'si:facebook', components: ['com.facebook.katana/com.facebook.katana.LoginActivity'] },
  { id: 'tiktok', glyph: 'si:tiktok', components: ['com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity', 'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity'] },
  { id: 'snapchat', glyph: 'si:snapchat', components: ['com.snapchat.android/com.snap.mushroom.MainActivity'] },
  { id: 'discord', glyph: 'si:discord', components: ['com.discord/com.discord.main.MainActivity'] },
  { id: 'telegram', glyph: 'si:telegram', components: ['org.telegram.messenger/org.telegram.ui.LaunchActivity'] },
  { id: 'reddit', glyph: 'si:reddit', components: ['com.reddit.frontpage/com.reddit.launch.main.MainActivity'] },
  { id: 'x', glyph: 'si:x', components: ['com.twitter.android/com.twitter.android.StartActivity'] },

  // Music, video, AI
  { id: 'spotify', glyph: 'si:spotify', components: ['com.spotify.music/com.spotify.music.MainActivity'] },
  { id: 'netflix', glyph: 'si:netflix', components: ['com.netflix.mediaclient/com.netflix.mediaclient.ui.launch.UIWebViewActivity'] },
  { id: 'chatgpt', glyph: 'mdi:creation', components: ['com.openai.chatgpt/com.openai.chatgpt.MainActivity'] },
  { id: 'claude', glyph: 'si:claude', components: ['com.anthropic.claude/com.anthropic.claude.mainactivity.MainActivity', 'com.anthropic.claude/com.anthropic.claude.MainActivity'] },
  { id: 'perplexity', glyph: 'si:perplexity', components: ['ai.perplexity.app.android/ai.perplexity.app.android.ui.main.MainActivity'] },

  // Work and money
  { id: 'teams', glyph: 'mdi:microsoft-teams', components: ['com.microsoft.teams/com.microsoft.skype.teams.Launcher'] },
  { id: 'slack', glyph: 'mdi:slack', components: ['com.Slack/slack.app.ui.HomeActivity', 'com.Slack/com.Slack.ui.HomeActivity'] },
  { id: 'starling', glyph: 'si:starlingbank', components: ['com.starlingbank.android/com.starlingbank.android.view.splash.SplashActivity'] },
  { id: 'monzo', glyph: 'si:monzo', components: ['co.uk.getmondo/co.uk.getmondo.splash.SplashActivity'] },
  { id: 'revolut', glyph: 'si:revolut', components: ['com.revolut.revolut/com.revolut.ui.splash.SplashActivity'] },
  { id: 'paypal', glyph: 'si:paypal', components: ['com.paypal.android.p2pmobile/com.paypal.android.p2pmobile.startup.activities.StartupActivity'] },

  // Food, travel, tickets, home
  { id: 'justeat', glyph: 'si:justeat', components: ['com.justeat.app.uk/com.justeat.app.ui.launcher.LauncherActivity', 'com.justeat.app.uk/com.justeat.app.launcher.LauncherActivity'] },
  { id: 'greggs', glyph: 'mdi:food-croissant', components: ['com.greggs.rewards/com.greggs.rewards.MainActivity'] },
  { id: 'ubereats', glyph: 'si:ubereats', components: ['com.ubercab.eats/com.ubercab.eats.app.EatsMainActivity'] },
  { id: 'deliveroo', glyph: 'si:deliveroo', components: ['com.deliveroo.orderapp/com.deliveroo.orderapp.ui.activities.SplashActivity'] },
  { id: 'uber', glyph: 'si:uber', components: ['com.ubercab/com.ubercab.presidio.app.core.root.RootActivity'] },
  { id: 'ticketmaster', glyph: 'mdi:ticket', components: ['com.ticketmaster.mobile.android.uk/com.ticketmaster.mobile.android.library.ui.activity.SplashActivity'] },
  { id: 'tapo', glyph: 'si:tplink', components: ['com.tplink.iot/com.tplink.iot.view.welcome.SplashActivity'] },
  { id: 'airplus', glyph: 'mdi:air-filter', components: ['com.philips.ph.homecare/com.philips.ph.homecare.activity.SplashActivity'] },
  { id: 'bible', glyph: 'mdi:book-cross', components: ['com.sirma.mobile.bible.android/com.youversion.mobile.android.screens.activities.SplashActivity', 'kjv.bible.kingjamesbible/kjv.bible.kingjamesbible.activity.SplashActivity'] },
  { id: 'duolingo', glyph: 'si:duolingo', components: ['com.duolingo/com.duolingo.app.LoginActivity'] },

  // Games
  { id: 'lordsknights', glyph: 'mdi:castle', components: ['com.xyrality.lordsandknights/com.xyrality.lordsandknights.activity.StartActivity'] },
  { id: 'kittensgame', glyph: 'mdi:cat', components: ['com.nuclearunicorn.kittensgame/com.nuclearunicorn.kittensgame.MainActivity'] },
  { id: 'durak', glyph: 'mdi:cards-playing', components: ['com.rstgames.durak/com.rstgames.durak.MainActivity'] },
  { id: 'durak3d', glyph: 'mdi:cards-playing-spade-multiple', components: ['com.rstgames.durak3d/com.rstgames.durak3d.MainActivity'] },
  { id: 'cells', glyph: 'mdi:bacteria-outline', components: [] },
  { id: 'plato', glyph: 'mdi:account-group', components: ['com.plato.android/com.plato.android.MainActivity'] },
  { id: 'kingshot', glyph: 'mdi:crown', components: [] },
  { id: 'albion', glyph: 'mdi:sword-cross', components: ['com.albiononline/com.albiononline.UnityPlayerActivity'] },
  { id: 'mythic', glyph: 'mdi:sword', components: [] },
  { id: 'galaxydefense', glyph: 'mdi:meteor', components: [] },
  { id: 'pengupace', glyph: 'mdi:penguin', components: [] },
  { id: 'thecell', glyph: 'mdi:cube-outline', components: [] },

  // These two apps
  { id: 'crimsonwidget', glyph: 'mdi:clock-outline', components: ['com.eldablos.crimson/com.eldablos.crimson.MainActivity'] },
  { id: 'budsbattery', glyph: 'mdi:headphones', components: ['com.eldablos.budswidget/com.eldablos.budswidget.MainActivity'] },
  { id: 'crimsonicons', glyph: 'mdi:palette', components: ['com.eldablos.crimsonicons/com.eldablos.crimsonicons.MainActivity'] },
];
