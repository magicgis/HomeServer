package app.ac;

import app.mqtt.MqttHandler;
import app.util.JsonTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import spark.Request;
import spark.Response;
import spark.Route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static spark.Spark.halt;

@Service
public class AcController {

	@Autowired
	private MqttHandler mqttHandler;

	@Autowired
	private AcDao acDao;

	private static final Logger logger = LoggerFactory.getLogger(AcController.class);

	/**
	 * TODO: Both on and off are the same; it should first check the state of the AC
	 */
	public Route turnOff = (Request request, Response response) -> {
		mqttHandler.publishMessage("ac/ac_alpha", AcFlashCode.ON_OFF.getFlashCode());
		response.status(200);
		return "OK";
	};

	/**
	 * TODO: Both on and off are the same; it should first check the state of the AC
	 */
	public Route turnOn = (Request request, Response response) -> {
		mqttHandler.publishMessage("ac/ac_alpha", AcFlashCode.ON_OFF.getFlashCode());
		response.status(200);
		return "OK";
	};

	public Route action = (Request request, Response response) -> {

		// TODO: convert name to device ID. For now, assume all names are device IDs.
		String name = request.params(":name");
		logger.info("Device: " + name + " Request: " + request.body());

		AcState acState = getRequest(request.body());
		if (acState == null || acState.equals(AcState.EMPTY_REQUEST)) {
			halt(400, "Empty request.");
		}

		// Without knowing the intended powered state of the AC, and to simplify the logic of determining what
		// to tell the AC what to do, we *require* the powered field be set.
		if (isUndefined(acState.getPowered())) {
			halt(400, "Powered state of AC must always be defined.");
		}

		// Without knowing the previous state of the AC, we can't reliably set the powered state correctly or
		// meaningfully change the temperature. Both fields are internal to the state of the AC and the controls
		// available implicitly act on that state.
		AcState prevState = acDao.getCurrentState(name);
		if (prevState.equals(AcState.EMPTY_REQUEST)) {
			halt(503, "Unknown State of AC. Unable to change state.");
		}

		List<AcFlashCode> commandSequence = new ArrayList<>();
		if (acState.getPowered() != prevState.getPowered()) {
			commandSequence.add(AcFlashCode.ON_OFF);
		}

		int temp = acState.getTemperature();
		int oldTemp = prevState.getTemperature();
		if (temp != oldTemp && !isUndefined(temp) && !isUndefined(oldTemp)) {
			int direction = temp < oldTemp ? -1 : 1;
			for (int i = 0; i < Math.abs(temp - oldTemp); i++) {
				commandSequence.add(direction < 0 ? AcFlashCode.TEMP_DOWN : AcFlashCode.TEMP_UP);
			}
		}

		int fanSpeed = acState.getFanSpeed();
		int oldFanSpeed = prevState.getFanSpeed();
		int dist = 0;
		if (!isUndefined(fanSpeed) && fanSpeed != oldFanSpeed) {
			if (fanSpeed < oldFanSpeed) {
				dist = 4 - oldFanSpeed + fanSpeed;
			} else {
				dist = fanSpeed - oldFanSpeed;
			}
			while (dist > 0) {
				commandSequence.add(AcFlashCode.FAN_DOWN);
				dist--;
			}
		}

		int mode = acState.getMode();
		int oldMode = prevState.getMode();
		if (!isUndefined(mode) && mode != oldMode) {
			commandSequence.add(AcFlashCode.getModeFlashCode(mode));
		}

		mqttHandler.publishCommandSequence("ac/" + name,
				commandSequence.stream()
						.map(AcFlashCode::getFlashCode)
						.collect(Collectors.toList()));

		response.status(200);
		return "success";
	};

	public Map<String, AcState> getAllAcStates() {
		Map<String, AcState> acStates = new HashMap<>();
		for (AcState ac : acDao.getAllAcStates()) {
			acStates.put(ac.getDeviceId(), ac);
		}
		return acStates;
	}

	private boolean isUndefined(int val) {
		return val == AcState.UNDF;
	}

	private boolean isUndefined(String val) {
		return val.toLowerCase().equals(AcState.UNDF);
	}

	private AcState getRequest(String payload) {
		AcState request = null;
		try {
			request = JsonTransformer.fromJson(payload, AcState.class);
		} catch (Exception e) {
			logger.error("Could not parse input request:\n" + payload, e);
		}
		return request;
	}
}
