/* SPDX-License-Identifier: Apache-2.0 */

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/device.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/kernel.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/byteorder.h>

#define PACKET_SIZE 32
#define PAYLOAD_SIZE 25
#define PROTOCOL_VERSION 1
#define CPS_MEASUREMENT_SIZE 14
#define CSC_MEASUREMENT_SIZE 11
#define SENSOR_LOCATION_REAR_HUB 0x0d
#define CSC_ERROR_CCC_CONFIG 0x81
#define SC_CP_SET_CUMULATIVE 0x01
#define SC_CP_RESPONSE 0x10
#define SC_CP_SUCCESS 0x01
#define SC_CP_NOT_SUPPORTED 0x02
#define SC_CP_INVALID_PARAMETER 0x03

static const struct device *const usb_uart = DEVICE_DT_GET_ONE(zephyr_cdc_acm_uart);
static uint8_t cps_measurement[CPS_MEASUREMENT_SIZE] = {0x30, 0x00};
static uint8_t csc_measurement[CSC_MEASUREMENT_SIZE] = {0x03};
static uint8_t sensor_location = SENSOR_LOCATION_REAR_HUB;
static uint32_t raw_wheel_revolutions;
static uint32_t wheel_revolution_offset;
static bool cps_subscribed;
static bool csc_subscribed;
static bool control_point_configured;
static int64_t last_packet_at;
static int64_t last_stale_notification_at;
K_MUTEX_DEFINE(measurement_lock);

static ssize_t read_location(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     void *buf, uint16_t len, uint16_t offset)
{
	return bt_gatt_attr_read(conn, attr, buf, len, offset, &sensor_location,
				 sizeof(sensor_location));
}

static ssize_t read_cps_feature(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				void *buf, uint16_t len, uint16_t offset)
{
	uint32_t feature = sys_cpu_to_le32(BIT(2) | BIT(3));

	return bt_gatt_attr_read(conn, attr, buf, len, offset, &feature, sizeof(feature));
}

static ssize_t read_csc_feature(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				void *buf, uint16_t len, uint16_t offset)
{
	uint16_t feature = sys_cpu_to_le16(BIT(0) | BIT(1));

	return bt_gatt_attr_read(conn, attr, buf, len, offset, &feature, sizeof(feature));
}

static void notify_measurements(void);

static void cps_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	cps_subscribed = value == BT_GATT_CCC_NOTIFY;
}

static void csc_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	csc_subscribed = value == BT_GATT_CCC_NOTIFY;
}

static void control_point_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	control_point_configured = value == BT_GATT_CCC_INDICATE;
}

static uint8_t control_response[3];
static struct bt_gatt_indicate_params control_indication;

static void control_indication_done(struct bt_conn *conn,
				    struct bt_gatt_indicate_params *params, uint8_t err)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);
	ARG_UNUSED(err);
}

static void indicate_control_response(struct bt_conn *conn, uint8_t request, uint8_t status);

static ssize_t write_control_point(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				   const void *buf, uint16_t len, uint16_t offset,
				   uint8_t flags)
{
	ARG_UNUSED(attr);
	ARG_UNUSED(flags);
	const uint8_t *request = buf;
	uint8_t status = SC_CP_NOT_SUPPORTED;

	if (!control_point_configured) {
		return BT_GATT_ERR(CSC_ERROR_CCC_CONFIG);
	}
	if (offset != 0 || len == 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	if (request[0] == SC_CP_SET_CUMULATIVE) {
		if (len == 5) {
			uint32_t requested = sys_get_le32(&request[1]);
			k_mutex_lock(&measurement_lock, K_FOREVER);
			wheel_revolution_offset = requested - raw_wheel_revolutions;
			k_mutex_unlock(&measurement_lock);
			status = SC_CP_SUCCESS;
		} else {
			status = SC_CP_INVALID_PARAMETER;
		}
	}
	indicate_control_response(conn, request[0], status);
	return len;
}

BT_GATT_SERVICE_DEFINE(cps_service,
	BT_GATT_PRIMARY_SERVICE(BT_UUID_CPS),
	BT_GATT_CHARACTERISTIC(BT_UUID_GATT_CPS_CPM, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(cps_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(BT_UUID_GATT_CPS_CPF, BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ, read_cps_feature, NULL, NULL),
	BT_GATT_CHARACTERISTIC(BT_UUID_SENSOR_LOCATION, BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ, read_location, NULL, NULL),
);

BT_GATT_SERVICE_DEFINE(csc_service,
	BT_GATT_PRIMARY_SERVICE(BT_UUID_CSC),
	BT_GATT_CHARACTERISTIC(BT_UUID_CSC_MEASUREMENT, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(csc_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(BT_UUID_SENSOR_LOCATION, BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ, read_location, NULL, NULL),
	BT_GATT_CHARACTERISTIC(BT_UUID_CSC_FEATURE, BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ, read_csc_feature, NULL, NULL),
	BT_GATT_CHARACTERISTIC(BT_UUID_SC_CONTROL_POINT,
			       BT_GATT_CHRC_WRITE | BT_GATT_CHRC_INDICATE,
			       BT_GATT_PERM_WRITE, NULL, write_control_point, NULL),
	BT_GATT_CCC(control_point_ccc_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

static void indicate_control_response(struct bt_conn *conn, uint8_t request, uint8_t status)
{
	control_response[0] = SC_CP_RESPONSE;
	control_response[1] = request;
	control_response[2] = status;
	memset(&control_indication, 0, sizeof(control_indication));
	/* Attribute 8 is the characteristic declaration; 9 is its value. */
	control_indication.attr = &csc_service.attrs[9];
	control_indication.func = control_indication_done;
	control_indication.data = control_response;
	control_indication.len = sizeof(control_response);
	(void)bt_gatt_indicate(conn, &control_indication);
}

static void notify_measurements(void)
{
	uint8_t power[CPS_MEASUREMENT_SIZE];
	uint8_t csc[CSC_MEASUREMENT_SIZE];

	k_mutex_lock(&measurement_lock, K_FOREVER);
	memcpy(power, cps_measurement, sizeof(power));
	memcpy(csc, csc_measurement, sizeof(csc));
	k_mutex_unlock(&measurement_lock);
	if (cps_subscribed) {
		/* Attribute 1 is the characteristic declaration; 2 is its value. */
		(void)bt_gatt_notify(NULL, &cps_service.attrs[2], power, sizeof(power));
	}
	if (csc_subscribed) {
		(void)bt_gatt_notify(NULL, &csc_service.attrs[2], csc, sizeof(csc));
	}
}

static uint16_t crc16_ccitt(const uint8_t *data, size_t len)
{
	uint16_t crc = 0xffff;

	for (size_t i = 0; i < len; i++) {
		crc ^= (uint16_t)data[i] << 8;
		for (int bit = 0; bit < 8; bit++) {
			crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021)
					     : (uint16_t)(crc << 1);
		}
	}
	return crc;
}

static void apply_packet(const uint8_t packet[PACKET_SIZE])
{
	uint16_t expected = sys_get_le16(&packet[PACKET_SIZE - 2]);
	if (packet[0] != 'P' || packet[1] != 'P' || packet[2] != PROTOCOL_VERSION ||
	    packet[4] != PAYLOAD_SIZE ||
	    crc16_ccitt(packet, PACKET_SIZE - 2) != expected) {
		return;
	}

	k_mutex_lock(&measurement_lock, K_FOREVER);
	memcpy(cps_measurement, &packet[5], CPS_MEASUREMENT_SIZE);
	memcpy(csc_measurement, &packet[5 + CPS_MEASUREMENT_SIZE], CSC_MEASUREMENT_SIZE);
	raw_wheel_revolutions = sys_get_le32(&csc_measurement[1]);
	uint32_t adjusted_wheel = raw_wheel_revolutions + wheel_revolution_offset;
	sys_put_le32(adjusted_wheel, &cps_measurement[4]);
	sys_put_le32(adjusted_wheel, &csc_measurement[1]);
	last_packet_at = k_uptime_get();
	k_mutex_unlock(&measurement_lock);
	notify_measurements();
}

static uint8_t receive_packet[PACKET_SIZE];
static size_t receive_position;

static void receive_byte(uint8_t value)
{
	if (receive_position == 0 && value != 'P') {
		return;
	}
	if (receive_position == 1 && value != 'P') {
		receive_position = value == 'P' ? 1 : 0;
		return;
	}
	receive_packet[receive_position++] = value;
	if (receive_position == PACKET_SIZE) {
		apply_packet(receive_packet);
		receive_position = 0;
	}
}

static const struct bt_data advertising_data[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
	BT_DATA_BYTES(BT_DATA_UUID16_ALL,
		      BT_UUID_16_ENCODE(BT_UUID_CPS_VAL),
		      BT_UUID_16_ENCODE(BT_UUID_CSC_VAL)),
	BT_DATA_BYTES(BT_DATA_GAP_APPEARANCE, 0x84, 0x04),
};

static const struct bt_data scan_response[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static int start_advertising(void)
{
	const struct bt_le_adv_param parameters = BT_LE_ADV_PARAM_INIT(
		BT_LE_ADV_OPT_CONN | BT_LE_ADV_OPT_USE_IDENTITY,
		BT_GAP_ADV_FAST_INT_MIN_1, BT_GAP_ADV_FAST_INT_MAX_1, NULL);

	return bt_le_adv_start(&parameters, advertising_data, ARRAY_SIZE(advertising_data),
			       scan_response, ARRAY_SIZE(scan_response));
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(err);
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(reason);
	cps_subscribed = false;
	csc_subscribed = false;
	control_point_configured = false;
	int err = start_advertising();
	if (err != 0 && err != -EALREADY) {
		/* The main loop remains alive; a subsequent reset will retry. */
	}
}

BT_CONN_CB_DEFINE(connection_callbacks) = {
	.connected = connected,
	.disconnected = disconnected,
};

static void publish_stale_zero_if_needed(void)
{
	int64_t now = k_uptime_get();
	if (now - last_packet_at < 1500 || now - last_stale_notification_at < 1000) {
		return;
	}
	k_mutex_lock(&measurement_lock, K_FOREVER);
	cps_measurement[2] = 0;
	cps_measurement[3] = 0;
	k_mutex_unlock(&measurement_lock);
	last_stale_notification_at = now;
	notify_measurements();
}

int main(void)
{
	if (!device_is_ready(usb_uart)) {
		return 0;
	}
	if (bt_enable(NULL) != 0) {
		return 0;
	}
	if (IS_ENABLED(CONFIG_SETTINGS)) {
		settings_load();
	}
	if (start_advertising() != 0) {
		return 0;
	}
	last_packet_at = k_uptime_get();
	for (;;) {
		uint8_t value;
		while (uart_poll_in(usb_uart, &value) == 0) {
			receive_byte(value);
		}
		publish_stale_zero_if_needed();
		k_sleep(K_MSEC(2));
	}
	return 0;
}
