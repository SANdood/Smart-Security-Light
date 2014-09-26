/**
 *  Smart Security Light
 *
 *  Author: SmartThings
 *
 *
 * Adapted from SmartThings' Smart NightLight by Barry Burke
 *
 * Changes:
 *		2014/09/23		Added support for physical override:
 *						* If lights turned on manually, don't turn them off if motion stops
 *						  but DO turn them off at sunrise (in case the are forgotten)
 *						* Double-tap ON will stop the motion-initiated timed Off event
 *						* Double-tap OFF will keep the lights off until it gets light, someone manually
 *						  turns on or off the light, or another app turns on the lights.
 * 						* TO RE-ENABLE MOTION CONTROL: Manually turn OFF the lights (single tap)
 *		2014/09/24		Re-enabled multi-motion detector support. Still only a single switch (for now).
 *						Added option to flash the light to confirm double-tap overrides
 *						* Fixed the flasher resetting the overrides
 *		2014/09/25		More work fixing up overrides. New operation mode:
 *						* Manual ON any time overrides motion until next OFF (manual or programmatic)
 *						* Manual OFF resets to motion-controlled
 *						* Double-tap manual OFF turns lights off until next reset (ON or OFF) or tomorrow morning
 *						  (light or sunrise-driven)
 *		2014/09/26		Code clean up around overrides. Single ON tap always disables motion; OFF tap re-enables
 *						motion. Double-OFF stops motion until tomorrow (light/sunrise)
 *
 *
 */
definition(
	name: 		"Smart Security Light",
	namespace: 	"smartthings",
	author: 	"SmartThings & Barry Burke",
	description: "Turns on lights when it's dark and motion is detected.  Turns lights off when it becomes light or some time after motion ceases. Optionally allows for manual override.",
	category: 	"Convenience",
	iconUrl: 	"https://s3.amazonaws.com/smartapp-icons/Meta/light_motion-outlet-luminance.png",
	iconX2Url: 	"https://s3.amazonaws.com/smartapp-icons/Meta/light_motion-outlet-luminance@2x.png"
)

preferences {
	section("Control these lights..."){
		input "lights", "capability.switch", multiple: true, required: true
	}
	section("Turning on when it's dark and there's movement..."){
		input "motionSensor", "capability.motionSensor", title: "Where?", multiple: true, required: true
	}
	section("And then off when it's light or there's been no movement for..."){
		input "delayMinutes", "number", title: "Minutes?"
	}
	section("Using either on this light sensor (optional) or the local sunrise and sunset"){
		input "lightSensor", "capability.illuminanceMeasurement", required: false
        input "luxLevel", "number", title: "Darkness Lux level?", defaultValue: 50, required: true
	}
	section ("Sunrise offset (optional)...") {
		input "sunriseOffsetValue", "text", title: "HH:MM", required: false
		input "sunriseOffsetDir", "enum", title: "Before or After", required: false, metadata: [values: ["Before","After"]]
	}
	section ("Sunset offset (optional)...") {
		input "sunsetOffsetValue", "text", title: "HH:MM", required: false
		input "sunsetOffsetDir", "enum", title: "Before or After", required: false, metadata: [values: ["Before","After"]]
	}
	section ("Zip code (optional, defaults to location coordinates when location services are enabled)...") {
		input "zipCode", "text", required: false
	}
	section ("Overrides") {
    	paragraph "Manual ON disables motion control. Manual OFF re-enables motion control."
		input "physicalOverride", "bool", title: "Physical override?", required: true, defaultValue: false
		paragraph "Double-tap OFF to lock light off until next ON or sunrise. Single-tap OFF to re-enable to motion-controlled."
		input "doubleTapOff", "bool", title: "Double-Tap OFF override?", required: true, defaultValue: true
        paragraph ""
        input "flashConfirm", "bool", title: "Flash lights to confirm overrides?", required: true, defaultValue: false
	}
}

def installed() {
	log.debug "Installed with settings: $settings"
	initialize()
}

def updated() {
	log.debug "Updated with settings: $settings"
	unsubscribe()
	unschedule()
	initialize()
}

def initialize() {
	
	// Let's ignore the current state, and figure it out as we go...
	state.physical = false
	state.lastStatus = "off"
    state.keepOff = false
    state.flashing = false
    lights.off()
	
	subscribe(motionSensor, "motion", motionHandler)
	
	if (physicalOverride) {
		subscribe(lights, "switch.on", lightsOnHandler)
		subscribe(lights, "switch.off", lightsOffHandler)
	}
	if (doubleTapOn || doubleTapOff) {
		subscribe(lights, "switch", switchHandler, [filterEvents: false])
	}

	if (lightSensor) {
		subscribe(lightSensor, "illuminance", illuminanceHandler, [filterEvents: false])
	}
	else {
		astroCheck()
		def sec = Math.round(Math.floor(Math.random() * 60))
		def min = Math.round(Math.floor(Math.random() * 60))
		def cron = "$sec $min * * * ?"
		schedule(cron, astroCheck) // check every hour since location can change without event?
	}
}

def lightsOnHandler(evt) {				// if ANYTHING (besides me) turns ON the light, then exit "keepOff" mode
    if ( state.flashing ) { return }
    
    state.keepOff = false
}

def lightsOffHandler(evt) {				// if anything turns OFF the light, then reset to motion-controlled
    if ( state.flashing ) { return }
    
	state.physical = false
    state.lastStatus = "off"
}
 
def switchHandler(evt) {
    if ( state.flashing ) { return }
    
	log.debug "switchHandler: $evt.name: $evt.value"

	if (evt.isPhysical()) {
		if (evt.value == "on") {
        	if (physicalOverride) {
                log.debug "Override ON, disabling motion-control"
