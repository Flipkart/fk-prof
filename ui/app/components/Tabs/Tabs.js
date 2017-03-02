import React, { Component, PropTypes } from 'react';
import cx from 'classnames';
import prefixUtil from 'utils/prefixUtils';

import styles from './Tabs.css';

const TABS_SLIDE_AMOUNT = 160;

class Tabs extends Component {
	constructor () {
		super();
		this.state = {
			activeTab: 0,
			scrolledAmount: 0
		};

		// to track the animations
		this._scrollableAmount = 0;
		this._headerLengths = null; // will be populated on componentDidMount
		this._containerWidth = null;
	}

	componentDidMount () {
		this.setup();
	}

	componentWillReceiveProps (nextProps) {
		(typeof nextProps.activeTab !== 'undefined') &&
			(nextProps.activeTab <= (this._headerLengths && this._headerLengths.length - 1)) &&
			(nextProps.activeTab >= 0) && (nextProps.activeTab !== this.props.activeTab) &&
				this.setState(
					{activeTab: nextProps.activeTab},
					this.setup
				);
	}

	componentWillUnmount () {
		this.container = null;
	}

	setup () {
		return new Promise((resolve, reject) => {
			// TODO figure out why this is undefined inside an RAF
			const headerContainer = this.tabHeaders;
			this._containerWidth = this.container.clientWidth;
			this._headerLengths = [].slice.call(headerContainer.children)
				.slice(0, -1).map(h => h.offsetWidth);
			this._scrollableAmount = this._headerLengths &&
				this._headerLengths.reduce((a, b) => a + b, 0) - this._containerWidth;
			// if negative then no need to scroll as headers are less than container width
			if (this._scrollableAmount < 0) this._scrollableAmount = 0;
			resolve();
		}).then(_ => {
			this.updateSliderPosition();
			this.handleTabChange(this.state.activeTab);
			this.container && this.forceUpdate();
		});
	}

	updateSliderPosition (i) {
		if (typeof i === 'undefined') i = this.state.activeTab;
		const sliderStart = this._headerLengths.reduce((prev, next, index) => {
			if (index < i) {
				prev += next;
			}
			return prev;
		}, 0);

		this._sliderStart = sliderStart;
		this._sliderWidth = this._headerLengths[i];
	}

	handleTabChange = index => () => {
		if (index < 0 || (index > (this._headerLengths.length - 1))) return;
		if (index === this.state.activeTab) return;

		this.updateSliderPosition(index);
		this.setState({activeTab: index});

		// scroll tab into view
		// check if it's in view or not
		const tabPosition = this._headerLengths.reduce((prev, curr, i) => {
			if (index <= i) return prev;
			return prev + curr;
		}, 0);
		const currentTabWidth = this._headerLengths[index];
		const scrolledTabPosition = tabPosition - this.state.scrolledAmount;
		let newScrollAmount;
		if (scrolledTabPosition < 0) {
			newScrollAmount = this.state.scrolledAmount - Math.abs(scrolledTabPosition) - 10;
			this.setState({
				// subtract the absolute scrolled amount + some more to get it visible
				// from the left scrolling button if it's there
				scrolledAmount: newScrollAmount < 0 ? 0 : newScrollAmount
			});
		} else if (scrolledTabPosition + currentTabWidth >= this._containerWidth) {
			// out of right boundary, scroll
			newScrollAmount = tabPosition - this._containerWidth + currentTabWidth;
			this.setState({
				scrolledAmount: newScrollAmount
			});
		}

		// call parent with tab index
		this.props.onTabChange && this.props.onTabChange(index);
	}

	// TODO: fix this if-else jungle @ankeetmaini
	handleScroll = direction => () => {
		const totalScrollRight = this._scrollableAmount - this.state.scrolledAmount;

		if (direction === 'right') {
			if (totalScrollRight) {
				const scroll = totalScrollRight > TABS_SLIDE_AMOUNT
					? TABS_SLIDE_AMOUNT : totalScrollRight;
				this.setState({scrolledAmount: this.state.scrolledAmount + scroll});
			}
		} else {
			if (this.state.scrolledAmount) { // eslint-disable-line no-lonely-if
				const scroll = this.state.scrolledAmount > TABS_SLIDE_AMOUNT
					? TABS_SLIDE_AMOUNT : this.state.scrolledAmount;
				this.setState({scrolledAmount: this.state.scrolledAmount - scroll});
			}
		}
	}

	containerRef = c => (this.container = c);

	tabHeadersRef = c => (this.tabHeaders = c);

	render () {
		const tabs = React.Children.toArray(this.props.children)
			.reduce((prev, next) => {
				prev.headers.push(next.props.children[0]);
				prev.contents.push(next.props.children[1]);
				return prev;
			}, {headers: [], contents: []});

		const {_sliderStart, _sliderWidth} = this;
		// TODO account below behaviour with a prop sent in
		// to avoid subtracting for margin/padding
		const firstHeaderSliderStartFix = !this.state.activeTab && 12 || 0;
		const sliderStyle = _sliderWidth
			? prefixUtil({transform: `translateX(${_sliderStart + 12 + firstHeaderSliderStartFix}px) scaleX(${_sliderWidth - 24 - firstHeaderSliderStartFix})`}) : {};

		return (
			<div className={styles['tabs-container']} ref={this.containerRef}>
				<div className={styles['tabs-headers']}>
					<div style={{position: 'relative', overflow: 'hidden', lineHeight: '1.2em', paddingTop: 10}}>
						<div style={{width: (this._headerLengths && this._headerLengths.reduce((a, b) => a + b, 0)) + 20}}>
							<div style={
									Object.assign(
										{},
										prefixUtil({transform: `translateX(-${this.state.scrolledAmount}px)`}),
										{whiteSpace: 'nowrap'}
									)
								}
								ref={this.tabHeadersRef}
								className={styles['scroll-header']}>
								{
									tabs.headers.map((header, index) => {
										return (
											<div
												key={`th${index}`}
												className={
													cx({
														[styles['tab-header']]: true,
														[styles['tab-header--active']]: this.state.activeTab === index
													})
												}
												onClick={this.handleTabChange(index)}>
												{header}
											</div>
										);
									})
								}

								<div className={styles['tabs-headers-underline']}>
									<div
										className={styles['tabs-headers-slider']}
										style={sliderStyle} />
								</div>
							</div>
						</div>
						{
							this.state.scrolledAmount &&
								<span onClick={this.handleScroll('left')} className={styles['tabs-scroll-left']}>&lsaquo;</span>
						}
						{
							(this._scrollableAmount - this.state.scrolledAmount) &&
								<span onClick={this.handleScroll('right')} className={styles['tabs-scroll-right']}>&rsaquo;</span>
						}
					</div>
				</div>

				<div>
					<div className={styles['tab-content-wrapper']} style={this.props.contentStyle}>
						{tabs.contents.map((c, i) => (
							<div
								className={
									cx({
										[styles['hidden']]: i !== this.state.activeTab,
									})
								}
								key={i}
							>
								{c}
							</div>
						))}
					</div>
				</div>

			</div>
		);
	}
}

Tabs.defaultProps = {
	contentStyle: {}
};

Tabs.propTypes = {
	contentStyle: PropTypes.object,
	onTabChange: PropTypes.func
};

export default Tabs;
