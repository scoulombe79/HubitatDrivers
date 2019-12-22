
/**
 *
 *  Thermostat Sinopé TH1123ZB Driver
 *
 *  Version: 0.3
 *  0.1   (2019-12-20) => First release
 *  0.2   (2019-12-21) => Added Lock / Unlock setting / HealthCheck
 *  0.3   (2019-12-21) => Fixed thermostat mode reporting, added thermostat mode setting, added power reporting (?)
 *
 *  Author: scoulombe
 *
 *  Date: 2019-12-22
 *
 *  Sources:
 *  Sinopé => https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/sinope-technologies/th1123zb-th1124zb-sinope-thermostat.src/th1123zb-th1124zb-sinope-thermostat.groovy
 *
 */

preferences
{
  input("BacklightAutoDimParam", "enum", title:"Backlight setting (default: sensing)", description: "On Demand or Sensing", options: ["On Demand", "Sensing"], multiple: false, required: false)
  input("DisableOutdorTemperatureParam", "bool", title: "disable outdoor temperature", description: "Set it to true to Disable outdoor temperature on the thermostat")
  input("keyboardLockParam", "bool", title: "enable the lock", description: "Set to true to enable the lock on the thermostat")
  input("trace", "bool", title: "Trace", description:"Set it to true to enable tracing")
}

metadata
{
  definition(name: "TH1123ZB Sinope Thermostat", namespace: "scoulombe", author: "scoulombe", ocfDeviceType: "oic.d.thermostat") {
    capability "Configuration"
    capability "Refresh"
    capability "Temperature Measurement"
    capability "Thermostat Heating Setpoint"
    capability "ThermostatMode"
    capability "Lock"
    capability "HealthCheck"
    capability "PowerMeter"

    fingerprint manufacturer: "Sinope Technologies", model: "TH1123ZB", deviceJoinName: "Sinope TH1123ZB Thermostat", inClusters: "0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01", outClusters: "0019,FF01"
  }
}

def installed()
{
  if (settings.trace)
    log.trace "TH1123ZB >> installed()"

  initialize()
}

def updated()
{
  if (settings.trace)
    log.trace "TH1123ZB >> updated()"

  if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000)
  {
    state.updatedLastRanAt = now()

    if (settings.trace)
      log.trace "TH1123ZB >> updated() => Device is now updated"

    try
    {
      unschedule()
    }
    catch (e)
    {
    }

    runIn(1, refresh_misc)
    runEvery15Minutes(refresh_misc)
  }
}

def configure()
{
  if (settings.trace)
    log.trace "TH1123ZB >> configure()"

  // Allow 30 min without receiving temperature report
  return sendEvent(name: "checkInterval", value: 30*60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
}

void initialize()
{
  if (settings.trace)
    log.trace "TH1123ZB >> initialize()"

  runIn(2, refresh)
  runEvery15Minutes(refresh_misc)
}

def ping()
{
  refresh()
}

def uninstalled()
{
  try
  {
    unschedule()
  }
  catch (e)
  {
  }
}

def parse(String description)
{
  def result = []
  def scale = getTemperatureScale()
  state?.scale = scale
  def cluster = zigbee.parse(description)

  if (description?.startsWith("read attr -"))
  {
    def descMap = zigbee.parseDescriptionAsMap(description)

    result += createCustomMap(descMap)

    if (descMap.additionalAttrs)
    {
      def mapAdditionnalAttrs = descMap.additionalAttrs

      mapAdditionnalAttrs.each { add ->
        add.cluster = descMap.cluster
        result += createCustomMap(add)
      }
    }
  }
  else if (!description?.startsWith("catchall:"))
    log.trace "TH1123ZB >> parse(description) ==> " + description

  return result
}

def createCustomMap(descMap)
{
  def result = null
  def map = [: ]
  def scale = getTemperatureScale()

  if (descMap.cluster == "0201" && descMap.attrId == "0000")
  {
    map.name = "temperature"
    map.value = getTemperatureValue(descMap.value)
    sendEvent(name: map.name, value: map.value, unit: scale)
    sendEvent(name: "checkInterval", value: 30*60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "0008")
  {
    map.name = "heatingDemand"
    map.value = getHeatingDemand(descMap.value)
    sendEvent(name: map.name, value: map.value)
    def operatingState = (map.value.toInteger() < 10) ? "idle" : "heating"
    sendEvent(name: "thermostatOperatingState", value: operatingState)
  }
  else if (descMap.cluster == "0B04" && descMap.attrId == "050B")
  {
    map.name = "power"
    map.value = getActivePower(descMap.value)
    sendEvent(name: map.name, value: map.value)
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "0012")
  {
    map.name = "heatingSetpoint"
    map.value = getTemperatureValue(descMap.value, true)
    sendEvent(name: map.name, value: map.value, unit: scale)
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "0014")
  {
    map.name = "heatingSetpoint"
    map.value = getTemperatureValue(descMap.value, true)
    sendEvent(name: map.name, value: map.value, unit: scale)
  }
  else if (descMap.cluster == "0201" && descMap.attrId == "001C")
  {
    map.name = "thermostatMode"
    map.value = getModeMap()[descMap.value]
    sendEvent(name: map.name, value: map.value)
  }
  else if (descMap.cluster == "0204" && descMap.attrId == "0001")
  {
    map.name = "lock"
    map.value = getLockMap()[descMap.value]
    sendEvent(name: map.name, value: map.value)
  }
  else
    log.trace "TH1123ZB >> createCustomMap(descMap) ==> " + descMap

  return map
}

def getTemperatureValue(value, doRounding = false)
{
  def scale = state?.scale

  if (value != null)
  {
    double celsius = (Integer.parseInt(value, 16) / 100).toDouble()

    if (scale == "C")
    {
      if (doRounding)
      {
        def tempValueString = String.format('%2.1f', celsius)

        if (tempValueString.matches(".*([.,][456])"))
          tempValueString = String.format('%2d.5', celsius.intValue())
        else if (tempValueString.matches(".*([.,][789])"))
        {
          celsius = celsius.intValue() + 1
          tempValueString = String.format('%2d.0', celsius.intValue())
        }
        else
          tempValueString = String.format('%2d.0', celsius.intValue())

        return tempValueString.toDouble().round(1)
      }
      else
        return celsius.round(1)
    }
    else
      return Math.round(celsiusToFahrenheit(celsius))
  }
}

def getHeatingDemand(value)
{
  if (value != null)
  {
    def demand = Integer.parseInt(value, 16)

    return demand.toString()
  }
}

def getActivePower(value)
{
  if (value != null)
  {
    def activePower = Integer.parseInt(value, 16)

    return activePower
  }
}

def getModeMap()
{
  [
    "00": "off",
    "04": "heat"
  ]
}

def getLockMap()
{
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

def unlock()
{
  if (settings.trace)
    log.trace "TH1123ZB >> unlock()"

  sendEvent(name: "lock", value: "unlocked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x00)

  fireCommand(cmds)
}

def lock()
{
  if (settings.trace)
    log.trace "TH1123ZB >> lock()"

  sendEvent(name: "lock", value: "locked")

  def cmds = []
  cmds += zigbee.writeAttribute(0x0204, 0x0001, DataType.ENUM8, 0x01)

  fireCommand(cmds)
}

def refresh()
{
  if (settings.trace)
    log.trace "TH1123ZB >> refresh()"

  if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 20000)
  {
    state.updatedLastRanAt = now()

    state?.scale = getTemperatureScale()

    def cmds = []

    cmds += zigbee.readAttribute(0x0201, 0x0000)  // Rd thermostat Local temperature
    cmds += zigbee.readAttribute(0x0201, 0x0012)  // Rd thermostat Occupied heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x0008)  // Rd thermostat PI heating demand
    cmds += zigbee.readAttribute(0x0201, 0x001C)  // Rd thermostat System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001)  // Rd thermostat Keypad lock

    cmds += zigbee.readAttribute(0x0B04, 0x050B)  // Rd thermostat Active power ?

    cmds += zigbee.configureReporting(0x0201, 0x0000, DataType.INT16, 19, 301, 50)      // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, DataType.UINT8, 4, 300, 10)       // heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, DataType.INT16, 15, 302, 40)      // occupied heating setpoint
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 599, 0x64)    // configure reporting of active power ... ?

    return cmds
  }
  else
  {
    if (settings.trace)
      log.trace "TH1123ZB >> refresh() --- Ran within last 20 seconds so aborting"
  }
}

void refresh_misc()
{
  def cmds = []

  // Outdoor temperature
  if (!settings.DisableOutdorTemperatureParam)
  {
    float outdoorTemp;

    // Read some outdoor temperature here ...

    if (outdoorTemp)
    {
      cmds += zigbee.writeAttribute(0xFF01, 0x0011, DataType.UINT16, 10800, [:], 1000) // Set the outdoor temperature timeout to 3 hours
      cmds += zigbee.writeAttribute(0xFF01, 0x0010, DataType.INT16, zigbee.convertHexToInt(hex(outdoorTemp * 100)), [mfgCode: "0x119C"], 1000)
    }
  }
  else
    cmds += zigbee.writeAttribute(0xFF01, 0x0011, DataType.UINT16, 30) // Set the outdoor temperature timeout to 30sec

  // Backlight
  if (BacklightAutoDimParam == "On Demand")
    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0000)
  else
    cmds += zigbee.writeAttribute(0x0201, 0x0402, DataType.ENUM8, 0x0001)

  // Lock / Unlock
  if (keyboardLockParam != true)
    unlock()
  else
    lock()

  // Time
  def thermostatDate = new Date();
  def thermostatTimeSec = thermostatDate.getTime() / 1000;
  def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
  def currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800);

  cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, zigbee.convertHexToInt(hex(currentTimeToDisplay)), [mfgCode: "0x119C"])

  // °C or °F
  if (state?.scale == 'C')
    cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 0)  // °C on thermostat display
  else
    cmds += zigbee.writeAttribute(0x0204, 0x0000, DataType.ENUM8, 1)  // °F on thermostat display

  if (cmds)
    fireCommand(cmds)
}

def setHeatingSetpoint(degrees)
{
  def scale = getTemperatureScale()
  degrees = checkTemperature(degrees)
  def degreesDouble = degrees as Double
  String tempValueString

  if (scale == "C")
    tempValueString = String.format('%2.1f', degreesDouble)
  else
    tempValueString = String.format('%2d', degreesDouble.intValue())

  sendEvent(name: "heatingSetpoint", value: tempValueString, unit: scale)

  def celsius = (scale == "C") ? degreesDouble : (fahrenheitToCelsius(degreesDouble) as Double).round(1)

  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x12, DataType.INT16,  zigbee.convertHexToInt(hex(celsius * 100)))

  return cmds
}

void off()
{
  setThermostatMode('off')
}

void auto()
{
  setThermostatMode('auto')
}

void heat()
{
  setThermostatMode('heat')
}

void emergencyHeat()
{
  setThermostatMode('heat')
}

void cool()
{
  setThermostatMode('cool')
}

def getSupportedThermostatModes()
{
  if (!state?.supportedThermostatModes)
    state?.supportedThermostatModes = (device.currentValue("supportedThermostatModes")) ? device.currentValue("supportedThermostatModes").toString().minus('[').minus(']').tokenize(',') : ['off', 'heat']

  return state?.supportedThermostatModes
}

def setThermostatMode(mode)
{
  if (settings.trace)
    log.trace "TH1123ZB >> setThermostatMode(${mode})"

  mode = mode?.toLowerCase()
  def supportedThermostatModes = getSupportedThermostatModes()

  if (mode in supportedThermostatModes)
    "mode_$mode" ()
}

def mode_off()
{
  if (settings.trace)
    log.trace "TH1123ZB >> mode_off()"

  sendEvent(name: "thermostatMode", value: "off", data: [supportedThermostatModes: getSupportedThermostatModes()])

  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
  cmds += zigbee.readAttribute(0x0201, 0x0008)

  fireCommand(cmds)
}

def mode_heat()
{
  if (settings.trace)
    log.trace "TH1123ZB >> mode_heat()"

  sendEvent(name: "thermostatMode", value: "heat", data: [supportedThermostatModes: getSupportedThermostatModes()])

  def cmds = []
  cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 4)
  cmds += zigbee.readAttribute(0x0201, 0x0008)

  fireCommand(cmds)
}

private def checkTemperature(def number)
{
  def scale = getTemperatureScale()

  if (scale == 'F')
  {
    if (number < 41)
      number = 41
    else if (number > 86)
      number = 86
  }
  else //scale == 'C'
  {
    if (number < 5)
      number = 5
    else if (number > 30)
      number = 30
  }

  return number
}

private fireCommand(List commands)
{
  if (commands != null && commands.size() > 0)
  {
    if (settings.trace)
      log.trace("Executing commands:" + commands)

    for (String value : commands)
      sendHubCommand([value].collect {new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)})
  }
}

private hex(value)
{
  String hex = new BigInteger(Math.round(value).toString()).toString(16)

  return hex
}
