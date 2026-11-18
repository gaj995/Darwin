package io.github.gajdarwin.cast
import org.junit.Assert.*
import org.junit.Test
class PlaybackOwnershipTest {
 private val idle=PlaybackOwner("","idle","","none")
 @Test fun pendingExtractionYieldsToAnotherCast(){assertTrue(idle.replacedBy(PlaybackOwner("other","playing","New movie","direct"),"pending"))}
 @Test fun oldPlaybackCanContinueWhileReplacementPrepares(){val old=PlaybackOwner("old","playing","Old movie","direct");assertFalse(old.replacedBy(old.copy(phase="paused"),"pending"));assertFalse(old.replacedBy(idle,"pending"))}
 @Test fun expectedCastConfirmationNeverCancelsItself(){assertFalse(idle.replacedBy(PlaybackOwner("pending","preparing","New movie","direct"),"pending"))}
 @Test fun macPlaybackWithoutPhoneTokenAlsoTakesOver(){assertTrue(idle.replacedBy(PlaybackOwner("","playing","Mac movie","direct"),"pending"));val old=PlaybackOwner("old","playing","Old movie","direct");assertTrue(old.replacedBy(PlaybackOwner("","playing","Mac movie","direct"),"pending"))}
 @Test fun anExistingMacMovieIsNotMistakenForNewPlayback(){val mac=PlaybackOwner("","playing","Mac movie","direct");assertFalse(mac.replacedBy(mac.copy(phase="buffering"),"pending"));assertTrue(mac.replacedBy(mac.copy(title="Another movie"),"pending"))}
 @Test fun completionRequiresOurTokenAndEndedOrHomeState(){assertTrue(idle.completed("ours","ours",true));assertTrue(PlaybackOwner("ours","ended","Movie","direct").completed("ours","",true));assertFalse(idle.completed("ours","other",true))}
 @Test fun staleCompletionCannotAdvanceQueueOverAnotherOwner(){assertFalse(PlaybackOwner("other","playing","Other movie","direct").completed("ours","ours",true));assertFalse(PlaybackOwner("","playing","Mac movie","direct").completed("ours","ours",true))}
}
