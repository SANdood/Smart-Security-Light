/**
 *  Smart security Light
 *
 *  Author: SmartThings
 *
 *
 * Adapted from SmartThings' Smart NightLight by Barry Burke
 *
 * Changes:
 *		2014/09/23		Added support for physical override:
 *						*	If lights turned on manually, don't turn them off if motion stops
 *														  but DO turn them off at sunrise (in case the are forgotten)
 *
 *
 */
definition(
	name: "Smart Security Light",
	namespace: "smartthings",
	author: "SmartThings & Barry Burke",
	description: "Turns on lights when it's dark and motion is detected.  Turns lights off when it becomes light or some time after motion ceases. Optionally allows for manual override.",
	category: "Convenience",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/light_motion-outlet-luminance.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/light_motion-outlet-luminance@2x.png"
)

preferences {
	section("Control these lights..."){
		input "light", "capability.switch", multiple: false
	}
	section("Turning on when it's dark and there's movement..."){
		input "motionSensor", "capability.motionSensor", title: "Where?"
	}
	section("And then off when it's light or there's been no movement for..."){
		input "delayMinutes", "number", title: "Minutes?"
	}
	section("Using either on this light sensor (optional) or the local sunrise and sunset"){
		input "lightSensor", "capability.illuminanceMeasurement", required: false
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
		input "physicalOverride", "bool", title: "Physical override?", required: true, defaultValue: false
		input "doubleTapOn", "bool", title: "Double-Tap ON override?", required: true, defaultValue: true
		input "doubleTapOff", "bool", titleL "Double-Tap OFF override?", required: true, defaultValue: true
	}
}

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	unschedule()
	initialize()
}

def initialize() {
	subscribe(motionSensor, "motion", motionHandler)
	
	if (physicalOverride) {
		subscribe(light, "switch.on", lightsOnHandler)
		subscribe(light, "switch.off", lightsOffHandler)
	}
	if (doubleTapOn || doubleTapOff) {
		subscribe(light, "switch", switchHandler, [filterEvents: false])
	}

	if (light.latestValue( "switch" ) == "on") {
		state.physical = true
		state.lastStatus = "on"
	}
	else {
		state.physical = false
		state.lastStatus = "off"
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

def lightsOnHandler(evt) {
	if (evt.isPhysical()) {
		state.physical = true
	}
}

def lightsOffHandler(evt) {
//	if (evt.isPhysical()) {
		state.physical = false
/	}
}
 
def switchHandler(evt) {
	// use Event rather than DeviceState because we may be changing DeviceState to only store changed values
	def recentStates = light.eventsSince(new Date(now() - 4000), [all:true, max: 10]).findAll{it.name == "switch"}
	log.debug "${recentStates?.size()} STATES FOUND, LAST AT ${recentStates ? recentStates[0].dateCreated : ''}"

	if (evt.isPhysical()) {
		if (evt.value == "on") {
			if (state.lastStatus == "off")) {
				state.physical = true								// Manual on BEFORE motion on
			}
			else if (lastTwoStatesWere("on", recentStates, evt)) {
			   	log.debug "detected two taps, override motion"
		   		state.physical = true								// Manual override of PRIOR motion on
			}
		} 
		else if (evt.value == "off") {
			state.physical = false									// Somebody turned off the light
			if (lastTwoStatesWere("off", recentStates, evt)) {
				log.debug "detected two taps, shutting off"
																	// Double tap means "Keep off until..."
			}
		}
	}
}

private lastTwoStatesWere(value, states, evt) {
	def result = false
	if (states) {
		log.trace "unfiltered: [${states.collect{it.dateCreated + ':' + it.value}.join(', ')}]"
		def onOff = states.findAll { it.isPhysical() || !it.type }
		log.trace "filtered:   [${onOff.collect{it.dateCreated + ':' + it.value}.join(', ')}]"

		// This test was needed before the change to use Event rather than DeviceState. It should never pass now.
		if (onOff[0].date.before(evt.date)) {
			log.warn "Last state does not reflect current event, evt.date: ${evt.dateCreated}, state.date: ${onOff[0].dateCreated}"
			result = evt.value == value && onOff[0].value == value
		}
		else {
			result = onOff.size() > 1 && onOff[0].value == value && onOff[1].value == value
		}
	}
	result
}

def motionHandler(evt) {
	log.debug "$evt.name: $evt.value"

	if (state.physical) { return}	// ignore motion if lights were most recently turned on manually

	if (evt.value == "active") {
		if (enabled()) {
			log.debug "turning on light due to motion"
			light.on()
			state.lastStatus = "on"
		}
		state.motionStopTime = null
	}
	else {
		state.motionStopTime = now()
		if(delayMinutes) {
			runIn(delayMinutes*60, turnOffMotionAfterDelay, [overwrite: false])
		} else {
			turnOffMotionAfterDelay()
		}
	}
}

def illuminanceHandler(evt) {
	log.debug "$evt.name: $evt.value, lastStatus: $state.lastStatus, motionStopTime: $state.motionStopTime"

	def lastStatus = state.lastStatus					// its getting light now, we can turn off
	if (lastStatus != "off" && evt.integerValue > 50) {	// whether or not it was manually turned on
		light.off()
		state.lastStatus = "off"
		state.physical = false
	}
	else if (state.motionStopTime) {
		if (state.physical) { return }					// light was manually turned on

		if (lastStatus != "off") {
			def elapsed = now() - state.motionStopTime
			if (elapsed >= (delayMinutes ?: 0) * 60000L) {
				light.off()
				state.lastStatus = "off"
			}
		}
	}
	else if (lastStatus != "on" && evt.value < 30) {
		if ( state.physical ) { return }				// light already manually on
		light.on()
		state.lastStatus = "on"
		state.physical = false							// we turned them on...
	}
}

def turnOffMotionAfterDelay() {
	log.debug "In turnOffMotionAfterDelay"

	if (state.physical) { return }						// light was manually turned on

	if (state.motionStopTime && state.lastStatus != "off") {
		def elapsed = now() - state.motionStopTime
		if (elapsed >= (delayMinutes ?: 0) * 60000L) {
			light.off()
			state.lastStatus = "off"
		}
	}
}

def scheduleCheck() {
	log.debug "In scheduleCheck - skipping"
	//turnOffMotionAfterDelay()
}

def astroCheck() {
	def s = getSunriseAndSunset(zipCode: zipCode, sunriseOffset: sunriseOffset, sunsetOffset: sunsetOffset)
	state.riseTime = s.sunrise.time
	state.setTime = s.sunset.time
	log.debug "rise: ${new Date(state.riseTime)}($state.riseTime), set: ${new Date(state.setTime)}($state.setTime)"
}

private enabled() {
	def result
	if (lightSensor) {
		result = lightSensor.currentIlluminance < 30
	}
	else {
		def t = now()
		result = t < state.riseTime || t > state.setTime
	}
	result
}

private getSunriseOffset() {
	sunriseOffsetValue ? (sunriseOffsetDir == "Before" ? "-$sunriseOffsetValue" : sunriseOffsetValue) : null
}

private getSunsetOffset() {
	sunsetOffsetValue ? (sunsetOffsetDir == "Before" ? "-$sunsetOffsetValue" : sunsetOffsetValue) : null
}
