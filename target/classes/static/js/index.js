/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

var ws = new WebSocket('wss://' + location.host + '/call');
var video, camera;
var webRtcPeer_ps;
var webRtcPeer_pc;
var webRtcPeer_vs;
var webRtcPeer_vc;

window.onload = function() {
	console = new Console();
	video = document.getElementById('video');
    camera = document.getElementById('camera');
	disableStopButton();
}

window.onbeforeunload = function() {
	ws.close();
}

ws.onmessage = function(message) {
	var parsedMessage = JSON.parse(message.data);
	console.info('Received message: ' + message.data);

	switch (parsedMessage.id) {
	case 'presenterScreenResponse':
        presenterScreenResponse(parsedMessage);
		break;
    case 'presenterCameraResponse':
        presenterCameraResponse(parsedMessage);
        break;
	case 'viewerScreenResponse':
        viewerScreenResponse(parsedMessage);
		break;
    case 'viewerCameraResponse':
        viewerCameraResponse(parsedMessage);
        break;
    case 'iceCandidateScreen':
        if (webRtcPeer_ps) {
            webRtcPeer_ps.addIceCandidate(parsedMessage.candidate, function(error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
        }
        break;
    case 'iceCandidateCamera':
        if (webRtcPeer_pc) {
            webRtcPeer_pc.addIceCandidate(parsedMessage.candidate, function(error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
        }
        break;
	case 'iceCandidateViewerScreen':
        if (webRtcPeer_vs) {
            webRtcPeer_vs.addIceCandidate(parsedMessage.candidate, function(error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
        }
		break;
    case 'iceCandidateViewerCamera':
        if (webRtcPeer_vc) {
            webRtcPeer_vc.addIceCandidate(parsedMessage.candidate, function(error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
        }
        break;
	case 'stopCommunication':
		dispose();
		break;
	default:
		console.error('Unrecognized message', parsedMessage);
	}
}

function presenterCameraResponse(message) {
    if (message.response != 'accepted') {
        var errorMsg = message.message ? message.message : 'Unknow error';
        console.info('Call not accepted for the following reason: ' + errorMsg);
        dispose();
    } else {
        if (webRtcPeer_pc) {
            webRtcPeer_pc.processAnswer(message.sdpAnswer, function(error) {
                if (error)
                    return console.error(error);
            });
        }
    }
}

function presenterScreenResponse(message) {
	if (message.response != 'accepted') {
		var errorMsg = message.message ? message.message : 'Unknow error';
		console.info('Call not accepted for the following reason: ' + errorMsg);
		dispose();
	} else {
	    if (webRtcPeer_ps) {
            webRtcPeer_ps.processAnswer(message.sdpAnswer, function(error) {
                if (error)
                    return console.error(error);
            });
        }
	}
}

function viewerScreenResponse(message) {
	if (message.response != 'accepted') {
		var errorMsg = message.message ? message.message : 'Unknow error';
		console.info('Call not accepted for the following reason: ' + errorMsg);
		dispose();
	} else {
        webRtcPeer_vs.processAnswer(message.sdpAnswer, function(error) {
			if (error)
				return console.error(error);
		});
	}
}

function viewerCameraResponse(message) {
    if (message.response != 'accepted') {
        var errorMsg = message.message ? message.message : 'Unknow error';
        console.info('Call not accepted for the following reason: ' + errorMsg);
        dispose();
    } else {
        webRtcPeer_vc.processAnswer(message.sdpAnswer, function(error) {
            if (error)
                return console.error(error);
        });
    }
}

function presenter() {
	if (!webRtcPeer_ps) {
		showSpinner(video);

		var options = {
			localVideo : video,
			onicecandidate : onIceCandidate,
            sendSource: 'screen'
		}
        webRtcPeer_ps = new kurentoUtils.WebRtcPeer.WebRtcPeerSendonly(options,
				function(error) {
					if (error) {
						return console.error(error);
					}
                    webRtcPeer_ps.generateOffer(onOfferPresenter);
				});

        webRtcPeer_pc = new kurentoUtils.WebRtcPeer.WebRtcPeerSendonly({
        	localVideo : camera,
        	onicecandidate : onIceCandidateCamera
        }, 	function(error) {
        		if (error) {
        			return console.error(error);
        		}
            webRtcPeer_pc.generateOffer(onOfferPresenterCamera);
        	}
        )

		enableStopButton();
	}
}

function viewer() {
	if (!webRtcPeer_vs) {
		showSpinner(video);

		var options = {
			remoteVideo : video,
			onicecandidate : onIceCandidate
		}
        webRtcPeer_vs = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(options,
				function(error) {
					if (error) {
						return console.error(error);
					}
					this.generateOffer(onOfferViewer);
				});
        webRtcPeer_vc = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly({
                remoteVideo : camera,
                onicecandidate : onIceCandidateCamera
            },
            function(error) {
                if (error) {
                    return console.error(error);
                }
                this.generateOffer(onOfferViewerCamera);
            });

		enableStopButton();
	}
}

function onOfferPresenter(error, offerSdp) {
    if (error)
        return console.error('Error generating the offer');
    console.info('Invoking SDP offer callback function ' + location.host);
    var message = {
        id : 'ps',
        sdpOffer : offerSdp
    }
    sendMessage(message);
}

function onOfferPresenterCamera(error, offerSdp) {
    if (error)
        return console.error('Error generating the offer');
    console.info('Invoking SDP offer callback function ' + location.host);
    var message = {
        id : 'pc',
        sdpOffer : offerSdp
    }
    sendMessage(message);
}

function onOfferViewer(error, offerSdp) {
	if (error)
		return console.error('Error generating the offer');
	console.info('Invoking SDP offer callback function ' + location.host);
	var message = {
		id : 'vs',
		sdpOffer : offerSdp
	}
	sendMessage(message);
}

function onOfferViewerCamera(error, offerSdp) {
    if (error)
        return console.error('Error generating the offer');
    console.info('Invoking SDP offer callback function ' + location.host);
    var message = {
        id : 'vc',
        sdpOffer : offerSdp
    }
    sendMessage(message);
}

function onIceCandidateCamera(candidate) {
    console.log("Local candidate" + JSON.stringify(candidate));

    var message = {
        id : 'onIceCandidateCamera',
        candidate : candidate
    };
    sendMessage(message);
}

function onIceCandidate(candidate) {
	console.log("Local candidate" + JSON.stringify(candidate));

	var message = {
		id : 'onIceCandidateScreen',
		candidate : candidate
	};
	sendMessage(message);
}

function stop() {
	var message = {
		id : 'stop'
	}
	sendMessage(message);
	dispose();
}

function dispose() {
	if (webRtcPeer_ps) {
        webRtcPeer_ps.dispose();
        webRtcPeer_ps = null;
	}
    if (webRtcPeer_pc) {
        webRtcPeer_pc.dispose();
        webRtcPeer_pc = null;
    }
    if (webRtcPeer_vs) {
        webRtcPeer_vs.dispose();
        webRtcPeer_vs = null;
    }
    if (webRtcPeer_vc) {
        webRtcPeer_vc.dispose();
        webRtcPeer_vc = null;
    }
	hideSpinner(video);

	disableStopButton();
}

function disableStopButton() {
	enableButton('#presenter', 'presenter()');
	enableButton('#viewer', 'viewer()');
	disableButton('#stop');
}

function enableStopButton() {
	disableButton('#presenter');
	disableButton('#viewer');
	enableButton('#stop', 'stop()');
}

function disableButton(id) {
	$(id).attr('disabled', true);
	$(id).removeAttr('onclick');
}

function enableButton(id, functionName) {
	$(id).attr('disabled', false);
	$(id).attr('onclick', functionName);
}

function sendMessage(message) {
	var jsonMessage = JSON.stringify(message);
	console.log('Senging message: ' + jsonMessage);
	ws.send(jsonMessage);
}

function showSpinner() {
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].poster = './img/transparent-1px.png';
		arguments[i].style.background = 'center transparent url("./img/spinner.gif") no-repeat';
	}
}

function hideSpinner() {
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].src = '';
		arguments[i].poster = './img/webrtc.png';
		arguments[i].style.background = '';
	}
}

/**
 * Lightbox utility (to display media pipeline image in a modal dialog)
 */
$(document).delegate('*[data-toggle="lightbox"]', 'click', function(event) {
	event.preventDefault();
	$(this).ekkoLightbox();
});
