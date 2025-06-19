import time, ssl, board, sys
from digitalio import DigitalInOut, Direction
from analogio import AnalogIn
import adafruit_connection_manager
import adafruit_requests
from adafruit_wiznet5k.adafruit_wiznet5k import WIZNET5K
from adafruit_io.adafruit_io import IO_HTTP, AdafruitIO_RequestError
import adafruit_wiznet5k.adafruit_wiznet5k_socketpool as socketpool
from pwmio import PWMOut
import adafruit_minimqtt.adafruit_minimqtt as MQTT
import adafruit_dht
from adafruit_httpserver import Server, Request, Response, JSONResponse, POST
import asyncio
# import test_fan
button = DigitalInOut(board.GP2)
button.direction = Direction.INPUT

LARGEST_POSSIBLE_VALUE = 0x7FFF

# Initialize data for adafruit
try:
    from secrets import secrets
except ImportError:
    print("You need secrets.py to run this program. Please add them to the lib folder.")
    raise

# Initialize spi interface
import busio
cs = DigitalInOut(board.GP17)
spi_bus = busio.SPI(board.GP18, MOSI=board.GP19, MISO=board.GP16)


# Initialize ethernet interface with DHCP
eth = WIZNET5K(spi_bus, cs, is_dhcp = True)

# Change Mac Address of the device if multiple Pico boards are connected
eth.mac_address = bytearray([0x00, 0x08, 0xDC, 0x22, 0x33, 0x57])
# eth.ifconfig = (IP_ADDRESS, SUBNET_MASK, GATEWAY_ADDRESS, DNS_SERVER)


# Create a socket pool
pool = socketpool.SocketPool(eth)


#SFA30 device using UART
SFA30 = busio.UART(board.GP0, board.GP1,baudrate = 115200, bits = 8, parity = None, stop = 1)

# Reset I2C device and wait 10s before starting it.
SFA_reset = bytearray([0x7E ,0x00 ,0xD3 ,0x00 ,0x2C ,0x7E ])
SFA30.write(SFA_reset)
print("Preparing SFA30")
time.sleep(10)
print(SFA30.readline())
SFA_config = bytearray([0x7E ,0x00 ,0x00 ,0x01 ,0x00 ,0xFE ,0x7E ])
SFA30.write(SFA_config)
time.sleep(0.2)
SFA30.readline()

# Making the three parameters
def set_reading_values(recv_data):
    
     # Useful Data for the measurements
    useful_data = recv_data[5:-2]


    cur_para = 0
    readings = []
    tuple_to_value = {
        (0x7D, 0x5E): 0x7E,
        (0x7D, 0x5D): 0x7D,
        (0x7D, 0x31): 0x11,
        (0x7D, 0x33): 0x13
    }
    i = 0
    value_switch_flag = False # False as the first value
    first_value = 0
    second_value = 0
    
    while i < len(useful_data):
        if i + 1 < len(useful_data):
             # check if (data[i], data[i+1]) exists in tuple_to_value
            current_tuple = (useful_data[i], useful_data[i+1])
            if current_tuple in tuple_to_value:
                if not value_switch_flag:
                    value_switch_flag = True
                    first_value = tuple_to_value[current_tuple] 
                else:
                    value_switch_flag = False
                    second_value = tuple_to_value[current_tuple] 
                i += 2
            else:
                if not value_switch_flag:
                    value_switch_flag = True
                    if useful_data[i] > LARGEST_POSSIBLE_VALUE:
                       first_value = - (useful_data[i] & 0x7fff)
                    else:
                        first_value = useful_data[i]
                else:
                    value_switch_flag = False
                    if useful_data[i] > LARGEST_POSSIBLE_VALUE:
                        second_value = - (useful_data[i] & 0x7fff)
                    else:
                        second_value = useful_data[i]
                i += 1
                
            if not value_switch_flag:
                print(cur_para)
                scale = 5 if cur_para == 0 else (5*cur_para*20)
                readings.append((256*first_value + second_value) / scale)
                cur_para += 1
                first_value, second_value = 0, 0
        else:  
            if useful_data[i] > LARGEST_POSSIBLE_VALUE:
                second_value = - (useful_data[i] & 0x7fff)
            else:
                second_value = useful_data[i]
            scale = 5 if cur_para == 0 else (5*cur_para*20)
            readings.append((256*first_value + second_value) /scale)
            first_value, second_value = 0, 0
            i += 1
        # print(readings)
        # print(value_switch_flag)
   
    return readings


    
# small led
led = DigitalInOut(board.GP5)
led.direction = Direction.OUTPUT

# Relay Fan
fan = DigitalInOut(board.GP9)
fan.direction = Direction.OUTPUT
fan.value = False
recorded_temp = 0

# BoxLight
boxlight = PWMOut(board.GP12)
boxlight_pwm_stored = 65535 # We suppose the user turn the light to max first

# Initialize connection for SOcket 3
udp_server = pool.socket(type = 2)
udp_server.setsockopt(pool.SOL_SOCKET, pool.SO_REUSEADDR, 1)
udp_server.bind((eth.pretty_ip(eth.ip_address),8888))
udp_server.settimeout(0)

# Countdown Time
TIMEOUT_SEC = 30
start = time.monotonic()
local_network = False

led.value = 0
# Tablet IP_Address
tablet = ""
# Find connection using UDP
print(f"Waiting for DISCOVER_IOT on UDP port {8888}")
while True:
    try:
        data, addr = udp_server.recvfrom(128)
    
        if data == b"DISCOVER_IOT":
            reply = b"IOT_DEVICE:" + eth.pretty_ip(eth.ip_address).encode("utf-8")
            tablet = addr
            try:
                udp_server.sendto(reply, addr)
            except:
                print("Send data error. Check again")
            else:
                print(f"Replied to {addr}: {reply}")
                local_network = True
                data, addr = None, None
                break
    except OSError:
        pass
   
    if time.monotonic() - start > TIMEOUT_SEC:
        print("No discovery in ",TIMEOUT_SEC, "s. switching to IoT-platform mode")
        break
    time.sleep(0.05)


# HTTP server
from audiopwmio import PWMAudioOut as AudioOut
from audiomp3 import MP3Decoder
mp3 = open("/Start_up.mp3", "rb")
decoder = MP3Decoder(mp3)
audio = AudioOut(board.GP26)
# audio.play(decoder)

def auto_temp():
    
    global readings, recorded_temp
    recv_data = bytearray()
    
    # Obtain Data
    SFA_get_data = bytearray([0x7E ,0x00 ,0x03 ,0x01 ,0x02 ,0xF9 ,0x7E])
    SFA30.write(SFA_get_data)
    time.sleep(0.5)
    segmented_frame = SFA30.readline()
    
    while segmented_frame != None:
        recv_data.extend(segmented_frame)
        segmented_frame = SFA30.readline()
        
        # return JSONResponse(request,{"Temp": readings[2], "Humidity": readings[1],"HCHO": readings[0]})
    print(recv_data)
    readings = set_reading_values(recv_data)
    recorded_temp = readings[2]


    
last_request_time = time.monotonic()
power = True
readings = [0,0,0]
while power:
    
    #If connect thorugh local network
    if local_network:
        
        # NOn async
        try:
            # initialize the server for Socket 2
            server = Server(pool, "/static", debug=True)
            reconnect_second = 10
            
            @server.route("/")
            def base(request: Request):
                decoder.file = open("/Start_up.mp3","rb")
                audio.play(decoder)
                return JSONResponse(request,  {"text": "Hello from Circuit Python", "value":led.value})
              
            @server.route("/fan")
            def toggle_fan(request:Request):
               
                fan.value = not fan.value
            
                s = "Turned on" if fan.value else "Turned off"
                return Response(request, s)
        
            @server.route("/light")
            def toggle_light(request:Request):
               
                # led.value = not led.value
                # fan.value = led.value
                if boxlight.duty_cycle == 0:
                    boxlight.duty_cycle = boxlight_pwm_stored
                else:
                    boxlight.duty_cycle = 0
                s = "Turned on" if boxlight.duty_cycle > 0 else "Turned off"
                
                return Response(request, s)
                
            @server.route("/lightbox", POST)
            def lightbox_change(request:Request):
                global boxlight_pwm_stored
                uploaded_body = request.json()
                boxlight.duty_cycle = int(65535 * (uploaded_body["value"]/100))
                boxlight_pwm_stored = boxlight.duty_cycle
                return Response(request,"Complete toggle")
    
            
            
            @server.route("/SFA30")
            def check_temp(request: Request):
                # DHT verion
                # return JSONResponse(request,{"Temp": dht.temperature, "Humidity": dht.humidity})
                global readings, recorded_temp, fan
                # recv_data = bytearray()
                
                # # Obtain Data
                # SFA_get_data = bytearray([0x7E ,0x00 ,0x03 ,0x01 ,0x02 ,0xF9 ,0x7E])
                # SFA30.write(SFA_get_data)
                # time.sleep(0.5)
                # segmented_frame = SFA30.readline()
                
                # while segmented_frame != None:
                #     recv_data.extend(segmented_frame)
                #     segmented_frame = SFA30.readline()
                    
                #     # return JSONResponse(request,{"Temp": readings[2], "Humidity": readings[1],"HCHO": readings[0]})
                # print(recv_data)
                # readings = set_reading_values(recv_data)
                # recorded_temp = readings[2]

               
                
                return JSONResponse(request,{"Temp": readings[2], "Humidity": readings[1],"HCHO": readings[0], "fan":fan.value == 1})
               
           
                    
                
            @server.route("/key")
            def get_key(request: Request):
                
                
                if secrets["aio_key"] != None:
                    decoder.file = open("/success_2.mp3","rb")
                    audio.play(decoder)
                    return Response(request,secrets["aio_key"])
                else:
                    return Response(request,"NO_KEY")
    
          
                
                
                

            server.start(str(eth.pretty_ip(eth.ip_address)), 5000)
            server.socket_timeout = 30
            connection = True # To indicate the server is online
            while connection:
                
                auto_temp()
                # if recorded_temp > 24:
                #     fan.value = 1
                # else:
                    # fan.value = 0

                if not button.value:
                    fan.value = 1
                else:
                    fan.value = 0
                
                try:
                    server.poll()
                except (ConnectionError, RuntimeError) as e:
                    server.stop()
                    local_network = False
                    print("Connection Closed")
                    break
                #     print("No requests. Try UDP pinging")
                #     UDP_PING(udp_server)
                
                
                
                now = time.monotonic()
                if now - last_request_time >= reconnect_second:
                
                    print(last_request_time)
                    print("Ping to see if client still alive")
                    start_local = time.monotonic()
                    while True:
                        try:
                            data, addr = udp_server.recvfrom(128)
                    
                            if data == b"DISCOVER_IOT":
                                reply = b"IOT_DEVICE:" + eth.pretty_ip(eth.ip_address).encode("utf-8")
                                tablet = addr
                                try:
                                    udp_server.sendto(reply, addr)
                                except:
                                    print("Device slept/paused. Ping again.")
                                else:
                                    print(f"Replied to {addr}: {reply}")
                                    last_request_time = time.monotonic()
                                    local_network = True
                                    data, addr = None, None
                                    break
                        except OSError:
                            
                            pass
                        # except OSError:
                        #     print("some_msg_1")
                        #     pass
                        if now - start_local > 5:
                            last_request_time = time.monotonic()
                            print("No discovery in 5s. switching to IoT-platform mode")
                            server.stop()
                            local_network = False   
                            connection = False  # Break the server poll loop
                            break  # Break the UDP pinging loop
                            
              
                time.sleep(0.001)
    
        except:
            local_network = False
            power = False
            server.stop()
        
    else:

        #Initialize a requests session for Socket 1
        ssl_context = adafruit_connection_manager.get_radio_ssl_context(eth)
        # requests = adafruit_requests.Session(pool, ssl_context)

        # If reconnecte
        # MQTT Setup
        light_feed  = f"{secrets["aio_username"]}/feeds/light"
        temp_feed   = f"{secrets["aio_username"]}/feeds/temp"
        humid_feed  = f"{secrets["aio_username"]}/feeds/humid"
        online_feed = f"{secrets["aio_username"]}/feeds/online"
        conc_feed   = f"{secrets["aio_username"]}/feeds/conc"
        boxlight_feed  = f"{secrets["aio_username"]}/feeds/pwmlight"
        fan_feed  = f"{secrets["aio_username"]}/feeds/fan"
        
        # message = {}
        def connected(client, userdata, flags, rc):
            # This function will be called when the client is connected
            # successfully to the broker.
            print("Connected to Adafruit IO!")
            # Subscribe to all changes on the feed list.
            client.subscribe(light_feed)
            client.subscribe(boxlight_feed)
            client.subscribe(fan_feed)
            # client.subscribe(temp_feed)
            # client.subscribe(humid_feed)
            # client.subscribe(conc_feed)
            
    
        def disconnected(client, userdata, rc):
            # This method is called when the client is disconnected
            print("Disconnected from Adafruit IO!")
        
        def message(client, topic, message):
        # This method is called when a topic the client is subscribed to
        # has a new message.
            global boxlight_pwm_stored
            # print("Boxlight:",boxlight_pwm_stored)
            if topic == light_feed:
                boxlight.duty_cycle = boxlight_pwm_stored if int(message) else 0
                
            elif topic == fan_feed:
                fan.value = int(message)
            elif topic == boxlight_feed:
                
                boxlight.duty_cycle = int(65535 * (int(message)/100))
                boxlight_pwm_stored = boxlight.duty_cycle
                
                
        # Make MQTT Client        
        mqtt_client = MQTT.MQTT(
        broker="io.adafruit.com",
        username=secrets["aio_username"],
        password=secrets["aio_key"],
        is_ssl=True,
        socket_pool=pool,
        ssl_context=ssl_context
        )
    
        # Override basic functions
        mqtt_client.on_connect = connected
        mqtt_client.on_disconnect = disconnected
        mqtt_client.on_message = message
    
    
        timeout_client = 1313131311
        
        # Connect the client to the MQTT broker.
        print("Connecting to Adafruit IO...")
        mqtt_client.connect()
     
        # Obtain Data
        SFA_get_data = bytearray([0x7E ,0x00 ,0x03 ,0x01 ,0x02 ,0xF9 ,0x7E])
        SFA30.write(SFA_get_data)
        time.sleep(0.2)
        recv_data= bytearray()
        segmented_frame = SFA30.readline()
        while segmented_frame != None:
            recv_data.extend(segmented_frame)
            segmented_frame = SFA30.readline()
        readings = set_reading_values(recv_data)

        mqtt_client.publish(online_feed,1)
        if boxlight.duty_cycle > 0:
            mqtt_client.publish(light_feed, 1)
        else:
            mqtt_client.publish(light_feed, 0)
        
        mqtt_client.publish(temp_feed, readings[2])
        mqtt_client.publish(humid_feed, readings[1])
        mqtt_client.publish(conc_feed, readings[0])
        
        recorded_temp = readings[2]
        if recorded_temp >= 25:
            fan.value = 1
            mqtt_client.publish(fan_feed, 1)
        else:
            fan.value = 0
            mqtt_client.publish(fan_feed, 0)
            
        
        AVOID_THROTTLE_TIME = 15
        last_request_time = time.monotonic()
        change_mode_time = last_request_time
        mqtt_alive = True
        try:
            while not local_network:
                
                mqtt_client.loop()
                current = time.monotonic()
    
                # Send Data after certain time frame
                if current - last_request_time >= AVOID_THROTTLE_TIME:
                    
        
                    # Obtain Data
                    SFA_get_data = bytearray([0x7E ,0x00 ,0x03 ,0x01 ,0x02 ,0xF9 ,0x7E])
                    SFA30.write(SFA_get_data)
                    time.sleep(0.2)
                    recv_data= bytearray()
                    segmented_frame = SFA30.readline()
                    while segmented_frame != None:
                        recv_data.extend(segmented_frame)
                        segmented_frame = SFA30.readline()
                        
                    readings = set_reading_values(recv_data) 
                    recorded_temp = readings[2]
                    mqtt_client.publish(temp_feed, readings[2])
                    mqtt_client.publish(humid_feed, readings[1])
                    mqtt_client.publish(conc_feed, readings[0])
                    if recorded_temp >= 26 & fan.value == 0:
                        fan.value = 1
                        mqtt_client.publish(fan_feed, 1)
                    elif recorded_temp < 26 & fan.value == 1:
                        fan.value = 0
                        mqtt_client.publish(fan_feed, 0)

       
                    #DHT version
                    # mqtt_client.publish(temp_feed, dht.temperature)
                    # mqtt_client.publish(humid_feed, dht.humidity)
                    last_request_time = current
    
                # Check if local device exist or not
                if (current - change_mode_time) >= timeout_client:
                    print("Trying to ping device to see change to local or not")
                    # UDP_PING(udp_server)
                    
                    while True:
                        try:
                            data, addr = udp_server.recvfrom(128)
                        
                            if data == b"DISCOVER_IOT":
                                reply = b"IOT_DEVICE:" + eth.pretty_ip(eth.ip_address).encode("utf-8")
                                tablet = addr
                                try:
                                    udp_server.sendto(reply, addr)
                                except:
                                    print("Device paused/slept. Try next time.")
                                else:
                                    print(f"Replied to {addr}: {reply}")
                                    local_network = True
                                    data, addr = None, None
                                    break
                        except OSError:
                            pass
                      
                        if time.monotonic() - change_mode_time > 5:
                            print("No discovery in 5s. Continue to IoT-platform mode")
                            change_mode_time  = time.monotonic()
                            local_network = False
                            break
                    if local_network:
                        mqtt_client.publish(online_feed,0)
                        mqtt_client.disconnect()
    
                time.sleep(0.05)
        except (KeyboardInterrupt, OSError, TypeError) as e:
            power = False
            mqtt_client.publish(online_feed,0)
            mqtt_client.publish(light_feed,0)
            mqtt_client.publish(boxlight_feed,0)
            mqtt_client.publish(fan_feed,0)
            mqtt_client.disconnect()

          